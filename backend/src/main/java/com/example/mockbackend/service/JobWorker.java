package com.example.mockbackend.service;

/**
 * Processes a job asynchronously. Exactly one implementation is active per Spring profile:
 * MockJobWorker (profile "mock", default) or RealJobWorker (profile "real").
 * Implementations annotate {@link #dispatch(String, int)} with {@code @Async}; callers must be other beans
 * so the call goes through the proxy.
 */
public interface JobWorker {
    /**
     * Runs attempt {@code attempt} of the job (the value just saved by create or retry). The worker starts it only
     * through {@link JobTransitions#start}, so a job canceled meanwhile, or retried into a newer attempt, is left alone.
     */
    void dispatch(String jobId, int attempt);

    /** Reported as {@code workerType} by GET /api/v1/healthz. */
    String type();
}
