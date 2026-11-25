package com.example.mockbackend.service;

import java.util.function.LongSupplier;

/**
 * A token bucket for the whole server (not per client): {@code burst} batches at once, refilled at {@code perSecond}.
 * POST /api/v1/events is public, so this caps what any number of clients can make the server write (docs/METRICS.md
 * §6.4). A per-client limit is REVIEW_CHECKLIST #22 and still [계획]; this needs no client key.
 */
final class EventRateLimiter {
    private final double tokensPerNano;
    private final double capacity;
    private final LongSupplier nanoClock;
    private double tokens;
    private long last;

    EventRateLimiter(double perSecond, int burst, LongSupplier nanoClock) {
        if (perSecond <= 0 || burst < 1) {
            throw new IllegalArgumentException("events.rate needs batches-per-second > 0 and burst >= 1");
        }
        this.tokensPerNano = perSecond / 1_000_000_000d;
        this.capacity = burst;
        this.nanoClock = nanoClock;
        this.tokens = burst;
        this.last = nanoClock.getAsLong();
    }

    /** Takes one batch's token; false when the bucket is empty. */
    synchronized boolean tryTake() {
        long now = nanoClock.getAsLong();
        tokens = Math.min(capacity, tokens + Math.max(0, now - last) * tokensPerNano);
        last = now;
        if (tokens >= 1) {
            tokens -= 1;
            return true;
        }
        return false;
    }
}
