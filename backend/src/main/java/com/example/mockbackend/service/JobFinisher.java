package com.example.mockbackend.service;

import com.example.mockbackend.domain.Job;
import com.example.mockbackend.domain.JobStatus;
import com.example.mockbackend.exception.ErrorCode;
import com.example.mockbackend.repository.JobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * The only way a run of a job ends. Sets the terminal fields and the status in one save (readers never see
 * COMPLETED without its asset), then appends the events.jsonl line. Workers and InterruptedJobRecovery use it,
 * so every finished run is measured exactly once.
 */
@Component
@RequiredArgsConstructor
public class JobFinisher {
    private final JobRepository jobRepository;
    private final EventLog eventLog;

    /** COMPLETED with the base GLB at {@code resultPath}; durationMs (v0) counts from {@code workerStart}. */
    public void complete(Job job, String resultPath, Instant workerStart, String workerType) {
        Instant now = Instant.now();
        job.setResultPath(resultPath);
        job.setFinishedAt(now);
        job.setUpdatedAt(now);
        job.setDurationMs(Duration.between(workerStart, now).toMillis());
        job.setProgress(1.0);
        job.setStatus(JobStatus.COMPLETED);
        jobRepository.save(job);
        eventLog.recordFinished(job, workerType);
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
        eventLog.recordFinished(job, workerType);
    }
}
