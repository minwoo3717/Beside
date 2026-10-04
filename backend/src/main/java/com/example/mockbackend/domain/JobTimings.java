package com.example.mockbackend.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * Stage durations of one run of a job, in ms. Shared by JobResponse.timings (API v1) and events.jsonl
 * (docs/METRICS.md §1) so both always report the same numbers.
 * <ul>
 *   <li>queuedMs: PENDING so far; fixed once PROCESSING started or the job ended.</li>
 *   <li>processingMs: null before PROCESSING, running while PROCESSING, fixed after.</li>
 *   <li>totalMs: null until COMPLETED/FAILED.</li>
 * </ul>
 * Measured from queuedAt (accept, or the last retry).
 */
public record JobTimings(long queuedMs, Long processingMs, Long totalMs) {

    public static JobTimings of(Job job, Instant now) {
        Instant queuedAt = job.getQueuedAt() != null ? job.getQueuedAt() : job.getCreatedAt();
        Instant started = job.getProcessingStartedAt();
        Instant finished = job.getFinishedAt();
        Instant queuedEnd = started != null ? started : (finished != null ? finished : now);
        long queuedMs = millis(queuedAt, queuedEnd);
        Long processingMs = started == null ? null : millis(started, finished != null ? finished : now);
        Long totalMs = finished == null ? null : millis(queuedAt, finished);
        return new JobTimings(queuedMs, processingMs, totalMs);
    }

    private static long millis(Instant from, Instant to) {
        if (from == null || to == null) {
            return 0;
        }
        return Math.max(0, Duration.between(from, to).toMillis());
    }
}
