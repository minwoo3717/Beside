package com.example.mockbackend.service;

import com.example.mockbackend.domain.JobRun;
import com.example.mockbackend.domain.JobStatus;

/**
 * Published by JobFinisher after a run is measured (job_runs row and events.jsonl line), for notifications only.
 * Built from the same JobRun, so it carries the same numbers as the measurement.
 */
public record JobFinishedEvent(String jobId, int attempt, JobStatus status, String errorCode, Long totalMs,
                               String workerType, String modelVersion, boolean passthrough) {

    static JobFinishedEvent of(JobRun run) {
        return new JobFinishedEvent(run.getJobId(), run.getAttempt() == null ? 1 : run.getAttempt(), run.getStatus(),
                run.getErrorCode(), run.getTotalMs(), run.getWorkerType(), run.getModelVersion(),
                Boolean.TRUE.equals(run.getPassthrough()));
    }
}
