package com.example.mockbackend.service;

import com.example.mockbackend.domain.Job;
import com.example.mockbackend.domain.JobStatus;
import com.example.mockbackend.exception.ApiException;
import com.example.mockbackend.exception.ErrorCode;
import com.example.mockbackend.repository.IdempotencyKeyEntry;
import com.example.mockbackend.repository.IdempotencyKeyRepository;
import com.example.mockbackend.repository.JobRepository;
import com.example.mockbackend.service.preprocess.UploadCheck;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Queue cap (REVIEW_CHECKLIST #21, decided 2026-11-21) at cap 1: a request with the Idempotency-Key
 * of a request that is still being accepted gets that request's job, as it does without the cap, instead of a 503
 * while the first one holds the only place; and a key linked while a request asked for a place answers with its job.
 * The contract text says a same-key request gets the existing job "상한과 상관없이". Its own class because @MockBean
 * and @SpyBean replace beans for the whole context.
 */
@SpringBootTest(properties = {"admission.max-pending=1", "admission.retry-after-seconds=45"})
class JobServiceKeyOverlapTest {
    @TempDir
    static Path storage;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("storage.upload.dir", () -> storage.resolve("uploads").toString());
        registry.add("storage.result.dir", () -> storage.resolve("results").toString());
    }

    /** Takes the dispatch and runs nothing: a job stays as the request left it. */
    @MockBean
    JobWorker jobWorker;

    /** Holds the first request in the photo check (the check itself is off, as in mock). */
    @SpyBean
    UploadCheck uploadCheck;

    @SpyBean
    IdempotencyKeyRepository idempotencyKeys;

    @Autowired
    JobServiceImpl jobService;

    @Autowired
    JobRepository jobRepository;

    @Autowired
    MeterRegistry registry;

    @AfterEach
    void clear() {
        reset(uploadCheck, idempotencyKeys);
        idempotencyKeys.deleteAll();
        jobRepository.deleteAll(); // nothing runs them: PENDING rows would fill the next test's queue
    }

    @Test
    void aResendWhileTheFirstRequestIsStillBeingAcceptedGetsTheSameJob() throws Exception {
        CountDownLatch inCheck = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        doAnswer(call -> {
            if (first.getAndSet(false)) {
                inCheck.countDown();
                assertThat(release.await(30, TimeUnit.SECONDS)).isTrue();
            }
            return call.callRealMethod();
        }).when(uploadCheck).check(any());

        CompletableFuture<Job> firstRequest = CompletableFuture.supplyAsync(
                () -> jobService.submitJobV1(List.of(photo()), "same-key"));
        assertThat(inCheck.await(30, TimeUnit.SECONDS)).isTrue(); // it holds the only place now
        double refusedCreates = refusedCreates();
        assertThatThrownBy(() -> jobService.submitJobV1(List.of(photo()), null)).isInstanceOfSatisfying(ApiException.class,
                full -> assertThat(full.getCode()).isEqualTo(ErrorCode.QUEUE_FULL));

        Job resend = jobService.submitJobV1(List.of(photo()), "same-key"); // was 503 QUEUE_FULL
        release.countDown();
        Job firstJob = firstRequest.get(30, TimeUnit.SECONDS);

        assertThat(firstJob.getId()).isEqualTo(resend.getId());
        assertThat(jobRepository.count()).as("one job comes out of one key").isEqualTo(1);
        assertThat(jobRepository.findById(resend.getId()).orElseThrow().getStatus()).isEqualTo(JobStatus.PENDING);
        verify(jobWorker, times(1)).dispatch(anyString(), anyInt());
        assertThat(refusedCreates()).as("only the request without the key was refused").isEqualTo(refusedCreates + 1);
    }

    @Test
    void aKeyLinkedWhileTheRequestAskedForAPlaceAnswersItsJob() {
        Job queued = jobRepository.save(pendingJob()); // the only place
        IdempotencyKeyEntry link = idempotencyKeys.save(new IdempotencyKeyEntry("linked-key", queued.getId(), Instant.now()));
        // The replay check misses (the first request had not linked the key yet), the place is refused, and by then
        // the key is linked: the request answers with that job. (A spy of a repository proxy delegates; it has no
        // real method to call, so the second answer is the stored link itself.)
        doReturn(Optional.empty()).doReturn(Optional.of(link)).when(idempotencyKeys).findById("linked-key");
        double refusedCreates = refusedCreates();

        Job answer = jobService.submitJobV1(List.of(photo()), "linked-key"); // was 503 QUEUE_FULL

        assertThat(answer.getId()).isEqualTo(queued.getId());
        assertThat(jobRepository.count()).isEqualTo(1);
        assertThat(refusedCreates()).as("answered 202, not refused").isEqualTo(refusedCreates);
        verify(uploadCheck, never()).check(any());
        verify(jobWorker, never()).dispatch(anyString(), anyInt());
    }

    @Test
    void aKeyNobodyLinkedIsStillRefusedWhenTheQueueIsFull() {
        jobRepository.save(pendingJob());

        assertThatThrownBy(() -> jobService.submitJobV1(List.of(photo()), "new-key"))
                .isInstanceOfSatisfying(ApiException.class, full -> {
                    assertThat(full.getCode()).isEqualTo(ErrorCode.QUEUE_FULL);
                    assertThat(full.getJobId()).isNull();
                });
        assertThat(idempotencyKeys.findById("new-key")).isEmpty();
        assertThat(jobRepository.count()).isEqualTo(1);
    }

    private double refusedCreates() {
        return registry.get("beside.jobs.refused").tag("op", "create").counter().count();
    }

    private static MockMultipartFile photo() {
        return new MockMultipartFile("photos", "dog.png", "image/png", new byte[]{1, 2, 3});
    }

    private static Job pendingJob() {
        Instant now = Instant.now();
        Job job = new Job();
        job.setId(UUID.randomUUID().toString());
        job.setStatus(JobStatus.PENDING);
        job.setCreatedAt(now);
        job.setUpdatedAt(now);
        job.setQueuedAt(now);
        job.setProgress(0.0);
        job.setAttempt(1);
        return job;
    }
}
