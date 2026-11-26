package com.example.mockbackend.service;

import java.util.ArrayDeque;
import java.util.function.BooleanSupplier;

/**
 * The real worker's inference slots (inference.max-concurrency): a first-come line like a fair Semaphore, except that a
 * waiter can leave it. While a job waits it is asked every {@code recheckMs} whether it still wants a slot (still
 * PENDING in its attempt); a job canceled while it waits leaves the line within that time and gives its worker thread
 * back, keeping its place until then. With a plain Semaphore a canceled job held its @Async thread until it reached the
 * front, so cancel and retry repeated could park dead attempts on the eight worker threads and delay the cutout and the
 * offline-wait clock of live jobs (review finding, 2026-11-21). The question is asked outside the lock (it reads the
 * database), and a waiter that times out keeps its place: Semaphore.tryAcquire with a timeout would send it to the back.
 * <p>
 * The place is taken with {@link #join} when the dispatch starts, before the cutout, and kept through it: the line is
 * in dispatch order, which is the accepted-or-retried order the contract (JobQueue) and QueueEstimator promise. When
 * the place was taken only after the cutout, a retry whose cutout was reused (no CPU wait) got ahead of new jobs
 * accepted before it that were still in the cutout, so their queue.ahead and estimate came out one job short
 * (review finding, 2026-11-25). The cost, accepted as a low risk: a cutout that never returned (a native ONNX Runtime
 * hang; the cutout has a permit wait, preprocess.cutout.wait-ms, but no time limit of its own) would hold the head of
 * the line, and the jobs behind it would wait as PENDING with the slot free. RealJobQueueOrderTest covers the order.
 */
final class InferenceSlots {
    private final ArrayDeque<Object> line = new ArrayDeque<>();
    private final long recheckMs;
    /** Guarded by {@link #line}. */
    private int free;

    InferenceSlots(int slots, long recheckMs) {
        this.free = Math.max(1, slots);
        this.recheckMs = Math.max(1, recheckMs);
    }

    /** Takes a place at the end of the line; pass it to {@link #acquire}, or give it back with {@link #leave}. */
    Object join() {
        Object ticket = new Object();
        synchronized (line) {
            line.addLast(ticket);
        }
        return ticket;
    }

    /**
     * Gives a place back without a slot (the cutout before {@link #acquire} threw). Does nothing once the place took
     * a slot or already left, so a caller can always call it in a finally.
     */
    void leave(Object ticket) {
        synchronized (line) {
            if (line.remove(ticket)) {
                line.notifyAll(); // the next one may be first now
            }
        }
    }

    /**
     * Waits at the place {@code ticket} from {@link #join} for a slot. Returns true holding a slot (give it back with
     * {@link #release}), or false without one as soon as {@code stillWanted} answers false — asked once first and then
     * after each wake-up. Either way the place is gone afterwards. {@code stillWanted} runs outside the lock; what it
     * throws leaves the line and propagates.
     * @throws InterruptedException with the waiter out of the line and no slot held
     */
    boolean acquire(Object ticket, BooleanSupplier stillWanted) throws InterruptedException {
        boolean acquired = false;
        try {
            if (!stillWanted.getAsBoolean()) {
                return false;
            }
            while (true) {
                synchronized (line) {
                    if (takeIfFirst(ticket)) {
                        acquired = true;
                        return true;
                    }
                    line.wait(recheckMs);
                    if (takeIfFirst(ticket)) {
                        acquired = true;
                        return true;
                    }
                }
                if (!stillWanted.getAsBoolean()) {
                    return false;
                }
            }
        } finally {
            if (!acquired) {
                leave(ticket);
            }
        }
    }

    void release() {
        synchronized (line) {
            free++;
            line.notifyAll();
        }
    }

    /**
     * Makes every waiter ask {@code stillWanted} now instead of at its next recheck (the server is stopping,
     * {@link ShutdownSignal}: the real worker's question then answers false).
     */
    void wakeAll() {
        synchronized (line) {
            line.notifyAll();
        }
    }

    /** Jobs waiting in line now (for tests and logs). */
    int waiting() {
        synchronized (line) {
            return line.size();
        }
    }

    private boolean takeIfFirst(Object ticket) {
        if (free > 0 && line.peekFirst() == ticket) {
            line.removeFirst();
            free--;
            line.notifyAll(); // with free slots left, the next one may go too
            return true;
        }
        return false;
    }
}
