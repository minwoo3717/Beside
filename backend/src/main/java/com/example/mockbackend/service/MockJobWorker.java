package com.example.mockbackend.service;

import com.example.mockbackend.domain.Job;
import com.example.mockbackend.domain.JobStatus;
import com.example.mockbackend.exception.ErrorCode;
import com.example.mockbackend.repository.JobRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/**
 * Profile "mock" (default): no GPU. PENDING for pending-ms, PROCESSING for processing-ms, then COMPLETED
 * with the sample GLB, or FAILED (INFERENCE_FAILED) when an uploaded filename contains the fail token.
 * Separate bean so calls from JobServiceImpl pass through Spring's async proxy.
 */
@Service
@Profile("mock")
@RequiredArgsConstructor
public class MockJobWorker implements JobWorker {
    private final JobRepository jobRepository;
    private final Logger log = LoggerFactory.getLogger(MockJobWorker.class);

    @Value("${storage.result.sample:storage/results/sample-dog.glb}")
    private String sampleResultPath;

    @Value("${mock.worker.pending-ms:2000}")
    private long pendingMs;

    @Value("${mock.worker.processing-ms:3000}")
    private long processingMs;

    @Value("${mock.worker.fail-when-filename-contains:fail}")
    private String failToken;

    @Override
    public String type() {
        return "mock";
    }

    @Async
    @Override
    public void dispatch(String jobId) {
        Instant start = Instant.now();
        try {
            Job job = jobRepository.findById(jobId)
                    .orElseThrow(() -> new IllegalStateException("Job not found: " + jobId));
            Thread.sleep(pendingMs);
            Instant processingStarted = Instant.now();
            job.setProcessingStartedAt(processingStarted);
            job.setProgress(0.5);
            job.setUpdatedAt(processingStarted);
            job.setStatus(JobStatus.PROCESSING);
            jobRepository.save(job);
            log.info("jobId={} stage=PROCESSING", jobId);

            Thread.sleep(processingMs);
            Instant finished = Instant.now();
            if (shouldFail(job)) {
                job.setErrorCode(ErrorCode.INFERENCE_FAILED.name());
                job.setErrorMessage("Mock failure: an uploaded filename contains '" + failToken + "'");
                job.setProgress(null);
                job.setFinishedAt(finished);
                job.setUpdatedAt(finished);
                job.setStatus(JobStatus.FAILED);
                jobRepository.save(job);
                log.info("jobId={} stage=FAILED (mock trigger)", jobId);
                return;
            }
            job.setResultPath(sampleResultPath);
            job.setFinishedAt(finished);
            job.setUpdatedAt(finished);
            job.setDurationMs(Duration.between(start, finished).toMillis());
            job.setProgress(1.0);
            // Publish completion only after the download path and metadata are ready.
            job.setStatus(JobStatus.COMPLETED);
            jobRepository.save(job);
            log.info("jobId={} stage=COMPLETED durationMs={}", jobId, job.getDurationMs());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Job job = jobRepository.findById(jobId).orElse(null);
            if (job != null) {
                Instant now = Instant.now();
                job.setErrorCode(ErrorCode.INTERNAL_ERROR.name());
                job.setErrorMessage("Mock worker interrupted");
                job.setProgress(null);
                job.setFinishedAt(now);
                job.setUpdatedAt(now);
                job.setStatus(JobStatus.FAILED);
                jobRepository.save(job);
            }
            log.error("Mock worker interrupted for jobId={}", jobId, e);
        }
    }

    private boolean shouldFail(Job job) {
        if (failToken == null || failToken.isBlank()) {
            return false;
        }
        String token = failToken.toLowerCase(Locale.ROOT);
        return job.getUploads().stream()
                .anyMatch(upload -> upload.name() != null && upload.name().toLowerCase(Locale.ROOT).contains(token));
    }
}
