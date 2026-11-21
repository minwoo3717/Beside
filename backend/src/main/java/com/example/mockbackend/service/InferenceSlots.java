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

    /**
     * Waits for a slot in arrival order. Returns true holding a slot (give it back with {@link #release}), or false
     * without one as soon as {@code stillWanted} answers false — asked once before joining the line and then after
     * each wake-up. {@code stillWanted} runs outside the lock; what it throws leaves the line and propagates.
     * @throws InterruptedException with the waiter out of the line and no slot held
     */
    boolean acquire(BooleanSupplier stillWanted) throws InterruptedException {
        if (!stillWanted.getAsBoolean()) {
            return false;
        }
        Object ticket = new Object();
        synchronized (line) {
            line.addLast(ticket);
        }
        boolean acquired = false;
        try {
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
                synchronized (line) {
                    line.remove(ticket);
                    line.notifyAll(); // the next one may be first now
                }
            }
        }
    }

    void release() {
        synchronized (line) {
            free++;
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
