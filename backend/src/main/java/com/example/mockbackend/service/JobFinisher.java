package com.example.mockbackend.service;

import com.example.mockbackend.domain.Job;
import com.example.mockbackend.domain.JobRun;
import com.example.mockbackend.domain.JobStatus;
import com.example.mockbackend.domain.RunDetails;
import com.example.mockbackend.exception.ErrorCode;
import com.example.mockbackend.repository.JobRepository;
import com.example.mockbackend.repository.JobRunRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * The only way a run of a job ends. Sets the terminal fields and the status in one save (readers never see
 * COMPLETED without its asset), then measures the run: one job_runs row and one events.jsonl line, both built from
 * the same JobRun so the two sources cannot disagree. Workers and InterruptedJobRecovery use it, so every finished
 * run is measured exactly once. Measuring never changes the outcome: a failed row insert or file append is logged
 * and the job stays COMPLETED/FAILED as saved.
 */
@Component
@RequiredArgsConstructor
public class JobFinisher {
    private final JobRepository jobRepository;
    private final JobRunRepository jobRunRepository;
    private final EventLog eventLog;
    private final Logger log = LoggerFactory.getLogger(JobFinisher.class);

    /**
     * COMPLETED with the base GLB at {@code resultPath}; durationMs (v0) counts from {@code workerStart}.
     * {@code details} is what the run produced beyond the GLB (the /infer numbers); the mock worker passes RunDetails.NONE.
     */
    public void complete(Job job, String resultPath, Instant workerStart, String workerType, RunDetails details) {
        Instant now = Instant.now();
        job.setResultPath(resultPath);
        job.setFinishedAt(now);
        job.setUpdatedAt(now);
        job.setDurationMs(Duration.between(workerStart, now).toMillis());
        job.setProgress(1.0);
        job.setStatus(JobStatus.COMPLETED);
        jobRepository.save(job);
        measure(JobRun.of(job, now, workerType, details));
    }

    /** FAILED with a job failure code (docs/api/ERROR_CODES.md) and a developer message. */
    public void fail(Job job, ErrorCode code, String message, String workerType) {
        Instant now = Instant.now();
        job.setErrorCode(code.name());
        job.setErrorMessage(message);
        job.setProgress(null);
        job.setFinishedAt(now);
        job.setUpdatedAt(now);
        job.setStatus(JobStatus.FAILED);
        jobRepository.save(job);
        measure(JobRun.of(job, now, workerType, RunDetails.NONE));
    }

    /** Insert-only row first (finishing one attempt twice is a bug and must not overwrite the first measurement), then the line. */
    private void measure(JobRun run) {
        try {
            jobRunRepository.saveAndFlush(run);
        } catch (DataIntegrityViolationException twice) {
            log.error("jobId={} attempt={} finished twice; job_runs keeps the first row", run.getJobId(), run.getAttempt());
        } catch (RuntimeException e) {
            log.error("Could not store the job_runs row of jobId={} attempt={}: {}", run.getJobId(), run.getAttempt(), e.toString());
        }
        eventLog.recordFinished(run);
    }
}
