package com.example.mockbackend.service;

/**
 * Processes a job asynchronously. Exactly one implementation is active per Spring profile:
 * MockJobWorker (profile "mock", default) or RealJobWorker (profile "real").
 * Implementations annotate {@link #dispatch(String)} with {@code @Async}; callers must be other beans
 * so the call goes through the proxy.
 */
public interface JobWorker {
    void dispatch(String jobId);

    /** Reported as {@code workerType} by GET /api/v1/healthz. */
    String type();
}
