package com.example.mockbackend.service;

import com.example.mockbackend.domain.JobRun;
import com.example.mockbackend.domain.JobStatus;

/**
 * Published by JobFinisher after a run is measured (job_runs row and events.jsonl line), for notifications
 * (WebhookNotifier) and operational metrics (JobMetrics). Built from the same JobRun, so it carries the same numbers
 * as the measurement. queuedMs and processingMs came last (operational metrics); events made with the earlier
 * eight-field constructor leave them null.
 */
public record JobFinishedEvent(String jobId, int attempt, JobStatus status, String errorCode, Long totalMs,
                               String workerType, String modelVersion, boolean passthrough,
                               Long queuedMs, Long processingMs) {

    /** The shape before queuedMs and processingMs: both stay null. */
    public JobFinishedEvent(String jobId, int attempt, JobStatus status, String errorCode, Long totalMs,
                            String workerType, String modelVersion, boolean passthrough) {
        this(jobId, attempt, status, errorCode, totalMs, workerType, modelVersion, passthrough, null, null);
    }

    static JobFinishedEvent of(JobRun run) {
        return new JobFinishedEvent(run.getJobId(), run.getAttempt() == null ? 1 : run.getAttempt(), run.getStatus(),
                run.getErrorCode(), run.getTotalMs(), run.getWorkerType(), run.getModelVersion(),
                Boolean.TRUE.equals(run.getPassthrough()), run.getQueuedMs(), run.getProcessingMs());
    }
}
