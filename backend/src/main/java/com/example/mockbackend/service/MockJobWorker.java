package com.example.mockbackend.service;

import com.example.mockbackend.domain.Job;
import com.example.mockbackend.domain.JobStatus;
import com.example.mockbackend.domain.RunDetails;
import com.example.mockbackend.exception.ErrorCode;
import com.example.mockbackend.repository.JobRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Locale;

/**
 * Profile "mock" (default): no GPU. PENDING for pending-ms, PROCESSING for processing-ms, then COMPLETED
 * with the sample GLB, or FAILED (INFERENCE_FAILED) when an uploaded filename contains the fail token.
 * Separate bean so calls from JobServiceImpl pass through Spring's async proxy. Runs end through JobFinisher; an
 * interrupted worker (shutdown) or an unexpected error ends the run with INTERNAL_ERROR.
 */
@Service
@Profile("mock")
@RequiredArgsConstructor
public class MockJobWorker implements JobWorker {
    private final JobRepository jobRepository;
    private final JobFinisher jobFinisher;
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
            if (shouldFail(job)) {
                jobFinisher.fail(job, ErrorCode.INFERENCE_FAILED,
                        "Mock failure: an uploaded filename contains '" + failToken + "'", type());
                log.info("jobId={} stage=FAILED (mock trigger)", jobId);
                return;
            }
            jobFinisher.complete(job, sampleResultPath, start, type(), RunDetails.NONE);
            log.info("jobId={} stage=COMPLETED durationMs={}", jobId, job.getDurationMs());
        } catch (InterruptedException e) {
            // Record the failure before restoring the interrupt flag: JDBC calls can fail while it is set.
            try {
                markInterrupted(jobId);
            } catch (RuntimeException databaseClosing) {
                log.warn("jobId={} interrupted during shutdown; InterruptedJobRecovery fails it at the next startup", jobId);
            } finally {
                Thread.currentThread().interrupt();
            }
        } catch (RuntimeException unexpected) {
            // Escaping the @Async method would leave the job PENDING or PROCESSING until the next restart.
            failUnended(jobId, unexpected);
        }
    }

    /**
     * Ends the run INTERNAL_ERROR, reading the job again (the copy the run held may be ahead of the database when its
     * save failed). A run that already ended stays as it is. One attempt: when the failure cannot be written either
     * (database down) this only logs, and InterruptedJobRecovery fails the job at the next startup.
     */
    private void failUnended(String jobId, RuntimeException unexpected) {
        log.error("jobId={} mock worker error", jobId, unexpected);
        try {
            Job job = jobRepository.findById(jobId).orElse(null);
            if (job == null) {
                log.warn("jobId={} not found for mock worker", jobId);
                return;
            }
            if (job.getStatus() != JobStatus.PENDING && job.getStatus() != JobStatus.PROCESSING) {
                log.warn("jobId={} already {}; left as it is", jobId, job.getStatus());
                return;
            }
            jobFinisher.fail(job, ErrorCode.INTERNAL_ERROR,
                    "Mock worker error (" + unexpected.getClass().getSimpleName() + ")", type());
            log.warn("jobId={} stage=FAILED code=INTERNAL_ERROR ({})", jobId, unexpected.getClass().getSimpleName());
        } catch (RuntimeException notRecorded) {
            log.error("jobId={} could not record the failure ({}); InterruptedJobRecovery fails it at the next startup",
                    jobId, notRecorded.toString());
        }
    }

    private void markInterrupted(String jobId) {
        Job job = jobRepository.findById(jobId).orElse(null);
        if (job == null) {
            return;
        }
        jobFinisher.fail(job, ErrorCode.INTERNAL_ERROR, "Mock worker interrupted (server shutting down)", type());
        log.warn("jobId={} stage=FAILED (mock worker interrupted)", jobId);
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
