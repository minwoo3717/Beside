package com.example.mockbackend.service;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * queuedAt for new and retried jobs: the current time in milliseconds, but always later than the previous one this
 * server handed out. The queue order (QueueEstimator's {@code ahead}) is queuedAt order; two jobs submitted back to
 * back can read the same instant (the Windows clock is coarse), and the order would then fall back to their random
 * ids. A step of 1 ms on a tie keeps the order of submission without visibly changing the timings.
 */
@Component
public class QueueClock {
    private final AtomicReference<Instant> last = new AtomicReference<>(Instant.EPOCH);

    public Instant next() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        return last.updateAndGet(previous -> now.isAfter(previous) ? now : previous.plusMillis(1));
    }
}
