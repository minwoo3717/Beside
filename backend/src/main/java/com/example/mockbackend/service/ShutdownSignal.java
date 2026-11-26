package com.example.mockbackend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * "The server is stopping", raised when the application context starts to close: the job workers' long waits watch
 * it and end their runs at once, so a stop takes about a second instead of ending in a SIGKILL.
 * <p>
 * Why (failover drill 2026-11-26, finding 4, docs/ops/2026-11-26_failover-drills.md): since Spring Framework 6.1
 * (Boot 3.2) the {@code @Async} executor (applicationTaskExecutor) takes part in the lifecycle stop and waits for the
 * tasks it is running, up to spring.lifecycle.timeout-per-shutdown-phase. A run waiting for a stopped GPU
 * (inference.offline-wait-ms, 30 min in prod), for its inference slot, for /infer (inference.timeout-ms) or in the
 * mock's sleep never ends in that time, so every stop with such a job took the whole cap (30 s by Spring's default)
 * while {@code docker compose stop} gives 10 s: the backend died by SIGKILL (exit 137) before H2 was closed, and the
 * only line about it ("Failed to shut down 1 bean ... [applicationTaskExecutor]") would have come at 30 s.
 * <p>
 * Spring publishes ContextClosedEvent before the lifecycle stop and before any bean is destroyed, so the database is
 * still open when the runs end: each ends its job FAILED INTERNAL_ERROR "... interrupted (server shutting down)", as
 * an interrupted run always meant to (before, the executor interrupted the threads only after the database had
 * closed, and the next start's InterruptedJobRecovery had to fail the jobs).
 * <p>
 * Cooperative on purpose: it never calls Thread.interrupt. An interrupt that lands while a thread is inside an H2 call
 * can close the database file's channel; the waits see the signal instead ({@link #sleep}, {@link #check} in a wait's
 * loop, the wake-ups of {@link #onClose}) and end by throwing an InterruptedException of their own.
 */
@Component
public class ShutdownSignal implements ApplicationListener<ContextClosedEvent>, ApplicationContextAware {
    private static final Logger log = LoggerFactory.getLogger(ShutdownSignal.class);
    static final String MESSAGE = "server shutting down";

    private final CountDownLatch raised = new CountDownLatch(1);
    private final AtomicBoolean once = new AtomicBoolean();
    private final List<Runnable> wakeUps = new CopyOnWriteArrayList<>();
    private ApplicationContext context;

    @Override
    public void setApplicationContext(ApplicationContext context) {
        this.context = context;
    }

    /**
     * What a wait throws when the signal ends it. An InterruptedException, so it takes the path of an interrupted
     * run, but the thread was not interrupted: a catch that restores the interrupt flag skips this one.
     */
    public static final class Stopping extends InterruptedException {
        Stopping() {
            super(MESSAGE);
        }
    }

    /** True from the moment the context starts to close. */
    public boolean stopping() {
        return raised.getCount() == 0;
    }

    /** Throws {@link Stopping} once the server is stopping; does nothing before. */
    public void check() throws Stopping {
        if (stopping()) {
            throw new Stopping();
        }
    }

    /**
     * Thread.sleep that ends early with {@link Stopping} as soon as the server is stopping. A real interrupt of the
     * thread still ends it with a plain InterruptedException.
     */
    public void sleep(long millis) throws InterruptedException {
        if (raised.await(Math.max(0, millis), TimeUnit.MILLISECONDS)) {
            throw new Stopping();
        }
    }

    /** Restores the interrupt flag after {@code e}, unless {@code e} is this signal's (no interrupt happened). */
    public static void restoreInterrupt(InterruptedException e) {
        if (!(e instanceof Stopping)) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Runs {@code wakeUp} when the signal is raised (at once if it already is), after {@link #stopping} turned true:
     * for a wait on a monitor of its own, so that its waiters look again now instead of at their next recheck.
     */
    public void onClose(Runnable wakeUp) {
        wakeUps.add(wakeUp);
        if (stopping()) {
            wakeUp.run();
        }
    }

    /** Raises the signal (the context does it on ContextClosedEvent; tests that build a worker by hand call it). */
    public void raise() {
        if (!once.compareAndSet(false, true)) {
            return;
        }
        raised.countDown();
        for (Runnable wakeUp : wakeUps) {
            try {
                wakeUp.run();
            } catch (RuntimeException e) {
                log.warn("shutdown wake-up failed: {}", e.toString());
            }
        }
    }

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        // The management server's child context publishes its own close to this context too: only ours counts.
        if (context != null && event.getApplicationContext() != context) {
            return;
        }
        // The one line a stop writes before the context closes (prod's log was silent until the SIGKILL).
        log.info("Server stopping: job runs still waiting end now (FAILED INTERNAL_ERROR, retry with POST /api/v1/jobs/{id}/retry)");
        raise();
    }
}
