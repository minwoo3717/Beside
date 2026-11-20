package com.example.mockbackend.service;

import com.example.mockbackend.domain.Job;
import com.example.mockbackend.domain.JobStatus;
import com.example.mockbackend.exception.ApiException;
import com.example.mockbackend.exception.ErrorCode;
import com.example.mockbackend.repository.IdempotencyKeyRepository;
import com.example.mockbackend.repository.JobRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Queue cap (REVIEW_CHECKLIST #21, proposal before the contract meeting) at cap 1 with a worker that runs nothing:
 * a dispatch the executor rejects (shutdown) gives its place back, and simultaneous retries of one FAILED job still end
 * as one 202 and 409s, never a 503 for a job another retry is re-queuing (the cap-on twin of JobServiceRetryRaceTest).
 * Its own class because @MockBean replaces the worker for the whole context.
 */
@SpringBootTest(properties = {"admission.max-pending=1", "admission.retry-after-seconds=45"})
class JobServiceAdmissionTest {
    private static final int REQUESTS = 8;

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

    @Autowired
    JobServiceImpl jobService;

    @Autowired
    JobRepository jobRepository;

    @Autowired
    IdempotencyKeyRepository idempotencyKeys;

    @Autowired
    MeterRegistry registry;

    @AfterEach
    void clear() {
        idempotencyKeys.deleteAll();
        jobRepository.deleteAll(); // nothing runs them: PENDING rows would fill the next test's queue
    }

    @Test
    void aCreateWhoseDispatchIsRejectedGivesItsPlaceBack() {
        doThrow(new TaskRejectedException("executor shut down")).doNothing().when(jobWorker).dispatch(anyString());

        assertThatThrownBy(() -> jobService.submitJobV1(List.of(photo()), "late-key"))
                .isInstanceOf(TaskRejectedException.class);
        assertThat(jobRepository.count()).isZero();

        Job accepted = jobService.submitJobV1(List.of(photo()), null); // would be 503 if the place had leaked
        assertThat(accepted.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThatThrownBy(() -> jobService.submitJobV1(List.of(photo()), null))
                .isInstanceOfSatisfying(ApiException.class, full -> assertThat(full.getCode()).isEqualTo(ErrorCode.QUEUE_FULL));
    }

    @Test
    void aRetryWhoseDispatchIsRejectedGivesItsPlaceBack() {
        Job failed = savedFailedJob();
        doThrow(new TaskRejectedException("executor shut down")).doNothing().when(jobWorker).dispatch(anyString());

        assertThatThrownBy(() -> jobService.retry(failed.getId())).isInstanceOf(TaskRejectedException.class);
        assertThat(jobRepository.findById(failed.getId()).orElseThrow().getStatus()).isEqualTo(JobStatus.FAILED);

        Job retried = jobService.retry(failed.getId());
        assertThat(retried.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(retried.getAttempt()).isEqualTo(2);
    }

    @Test
    void simultaneousRetriesOfOneFailedJobAtCapOneAnswerOne202AndOnly409s() throws Exception {
        Job failed = savedFailedJob();
        double refusedRetries = registry.get("beside.jobs.refused").tag("op", "retry").counter().count();
        ExecutorService pool = Executors.newFixedThreadPool(REQUESTS);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Integer>> answers = new ArrayList<>();
            for (int i = 0; i < REQUESTS; i++) {
                answers.add(pool.submit(() -> {
                    go.await();
                    try {
                        jobService.retry(failed.getId());
                        return 202;
                    } catch (ApiException refused) {
                        assertThat(refused.getCode()).isEqualTo(ErrorCode.JOB_NOT_FAILED);
                        assertThat(refused.getJobId()).isEqualTo(failed.getId());
                        return refused.getStatus().value();
                    }
                }));
            }
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> answer : answers) {
                statuses.add(answer.get(30, TimeUnit.SECONDS));
            }
            assertThat(statuses).filteredOn(status -> status == 202).hasSize(1);
            assertThat(statuses).filteredOn(status -> status == 409).hasSize(REQUESTS - 1);
        } finally {
            pool.shutdownNow();
        }
        verify(jobWorker, times(1)).dispatch(failed.getId());
        Job retried = jobRepository.findById(failed.getId()).orElseThrow();
        assertThat(retried.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(retried.getAttempt()).isEqualTo(2);
        assertThat(registry.get("beside.jobs.refused").tag("op", "retry").counter().count())
                .as("no 503 was answered").isEqualTo(refusedRetries);

        // The queue is now full with that job: a retry of another FAILED job is refused and names it.
        Job other = savedFailedJob();
        assertThatThrownBy(() -> jobService.retry(other.getId())).isInstanceOfSatisfying(ApiException.class, full -> {
            assertThat(full.getCode()).isEqualTo(ErrorCode.QUEUE_FULL);
            assertThat(full.getJobId()).isEqualTo(other.getId());
        });
        assertThat(jobRepository.findById(other.getId()).orElseThrow().getStatus()).isEqualTo(JobStatus.FAILED);
    }

    private static MockMultipartFile photo() {
        return new MockMultipartFile("photos", "late.png", "image/png", new byte[]{1, 2, 3});
    }

    private Job savedFailedJob() {
        Instant then = Instant.now().minusSeconds(60);
        Job failed = new Job();
        failed.setId(UUID.randomUUID().toString());
        failed.setStatus(JobStatus.FAILED);
        failed.setCreatedAt(then);
        failed.setUpdatedAt(then);
        failed.setQueuedAt(then);
        failed.setFinishedAt(then);
        failed.setAttempt(1);
        failed.setErrorCode("INFERENCE_FAILED");
        failed.setErrorMessage("first attempt");
        return jobRepository.save(failed);
    }
}
