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
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * FAILED -> (retry) -> PENDING happens once per FAILED attempt, even when two POST /retry for the same job overlap
 * (scripts, curl, two clients): one gets 202 and dispatches attempt N+1, every other one gets JOB_NOT_FAILED.
 */
@SpringBootTest
class JobServiceRetryTest {
    private static final int RACERS = 8;
    private static final int ROUNDS = 20;

    @TempDir
    static Path storage;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("storage.upload.dir", () -> storage.resolve("uploads").toString());
        registry.add("storage.result.dir", () -> storage.resolve("results").toString());
    }

    @MockBean
    JobWorker jobWorker; // no run happens: the job stays as the retry left it

    @Autowired
    JobServiceImpl jobService;

    @Autowired
    JobRepository jobRepository;

    @Test
    void overlappingRetriesOfOneFailedJobDispatchTheNextAttemptOnce() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(RACERS);
        try {
            for (int round = 0; round < ROUNDS; round++) {
                String id = saveFailedJob();
                clearInvocations(jobWorker);
                CountDownLatch go = new CountDownLatch(1);
                List<Future<String>> answers = new ArrayList<>();
                for (int i = 0; i < RACERS; i++) {
                    answers.add(pool.submit(() -> {
                        go.await();
                        try {
                            jobService.retry(id);
                            return "ACCEPTED";
                        } catch (ApiException e) {
                            return e.getCode().name();
                        }
                    }));
                }
                go.countDown();
                List<String> results = new ArrayList<>();
                for (Future<String> answer : answers) {
                    results.add(answer.get(10, TimeUnit.SECONDS));
                }

                assertThat(results).as("round %d", round).containsOnlyOnce("ACCEPTED")
                        .filteredOn(result -> !result.equals("ACCEPTED"))
                        .containsOnly(ErrorCode.JOB_NOT_FAILED.name());
                verify(jobWorker, times(1)).dispatch(id);
                Job after = jobRepository.findById(id).orElseThrow();
                assertThat(after.getStatus()).isEqualTo(JobStatus.PENDING);
                assertThat(after.getAttempt()).as("round %d", round).isEqualTo(2);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void theConditionalUpdateClearsTheFailedAttemptAndChangesOnlyAFailedJob() {
        String id = saveFailedJob();
        Instant now = Instant.now();

        assertThat(jobRepository.startRetry(id, now, JobStatus.FAILED, JobStatus.PENDING)).isEqualTo(1);
        Job retried = jobRepository.findById(id).orElseThrow();
        assertThat(retried.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(retried.getAttempt()).isEqualTo(2);
        assertThat(retried.getErrorCode()).isNull();
        assertThat(retried.getErrorMessage()).isNull();
        assertThat(retried.getResultPath()).isNull();
        assertThat(retried.getHairResultPath()).isNull();
        assertThat(retried.getProcessingStartedAt()).isNull();
        assertThat(retried.getFinishedAt()).isNull();
        assertThat(retried.getProgress()).isEqualTo(0.0);
        assertThat(retried.getQueuedAt()).isEqualTo(retried.getUpdatedAt()).isAfter(retried.getCreatedAt());

        // Already PENDING (the other retry won): nothing changes.
        assertThat(jobRepository.startRetry(id, Instant.now(), JobStatus.FAILED, JobStatus.PENDING)).isZero();
        assertThat(jobRepository.findById(id).orElseThrow().getAttempt()).isEqualTo(2);
        assertThat(jobRepository.startRetry("no-such-job", Instant.now(), JobStatus.FAILED, JobStatus.PENDING)).isZero();
    }

    private String saveFailedJob() {
        Instant then = Instant.now().minusSeconds(60);
        Job failed = new Job();
        failed.setId(UUID.randomUUID().toString());
        failed.setStatus(JobStatus.FAILED);
        failed.setCreatedAt(then);
        failed.setUpdatedAt(then);
        failed.setQueuedAt(then);
        failed.setProcessingStartedAt(then);
        failed.setFinishedAt(then);
        failed.setProgress(null);
        failed.setAttempt(1);
        failed.setResultPath("/server/only/base.glb");
        failed.setErrorCode("INFERENCE_FAILED");
        failed.setErrorMessage("first attempt");
        jobRepository.save(failed);
        return failed.getId();
    }
}
