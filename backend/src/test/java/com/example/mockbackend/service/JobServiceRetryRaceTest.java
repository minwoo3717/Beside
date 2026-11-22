package com.example.mockbackend.service;

import com.example.mockbackend.domain.Job;
import com.example.mockbackend.domain.JobStatus;
import com.example.mockbackend.exception.ApiException;
import com.example.mockbackend.exception.ErrorCode;
import com.example.mockbackend.repository.JobRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * POST /retry is public: two devices or a double tap can retry the same FAILED job at once. Only one request may move
 * it to PENDING and dispatch a run; the others get 409 JOB_NOT_FAILED. Before, every request passed the FAILED check
 * and the GPU ran the same attempt once per request.
 */
@SpringBootTest
class JobServiceRetryRaceTest {
    private static final int REQUESTS = 8;

    @TempDir
    static Path storage;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("storage.upload.dir", () -> storage.resolve("uploads").toString());
        registry.add("storage.result.dir", () -> storage.resolve("results").toString());
    }

    /** Takes the dispatch and runs nothing: the job stays as the winning retry left it. */
    @MockBean
    JobWorker jobWorker;

    @Autowired
    JobServiceImpl jobService;

    @Autowired
    JobRepository jobRepository;

    @Test
    void simultaneousRetriesOfOneFailedAttemptDispatchOnce() throws Exception {
        Job failed = savedFailedJob();
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
        verify(jobWorker, times(1)).dispatch(failed.getId(), 2);

        Job retried = jobRepository.findById(failed.getId()).orElseThrow();
        assertThat(retried.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(retried.getAttempt()).isEqualTo(2);
        assertThat(retried.getErrorCode()).isNull();
        assertThat(retried.getErrorMessage()).isNull();
        assertThat(retried.getFinishedAt()).isNull();
        assertThat(retried.getProcessingStartedAt()).isNull();
        assertThat(retried.getProgress()).isEqualTo(0.0);
        assertThat(retried.getQueuedAt()).isAfter(failed.getQueuedAt());
        assertThat(retried.getUploads()).isEqualTo(failed.getUploads());

        // The PENDING attempt cannot be retried again.
        assertThatThrownBy(() -> jobService.retry(failed.getId()))
                .isInstanceOfSatisfying(ApiException.class,
                        refused -> assertThat(refused.getCode()).isEqualTo(ErrorCode.JOB_NOT_FAILED));
        verify(jobWorker, times(1)).dispatch(failed.getId(), 2);
    }

    private Job savedFailedJob() {
        Instant then = Instant.now().minusSeconds(60);
        Job failed = new Job();
        failed.setId(UUID.randomUUID().toString());
        failed.setStatus(JobStatus.FAILED);
        failed.setCreatedAt(then);
        failed.setUpdatedAt(then);
        failed.setQueuedAt(then);
        failed.setProcessingStartedAt(then);
        failed.setFinishedAt(then);
        failed.setAttempt(1);
        failed.setErrorCode("INFERENCE_FAILED");
        failed.setErrorMessage("first attempt");
        return jobRepository.save(failed);
    }
}
