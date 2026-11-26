package com.example.mockbackend.service;

import org.junit.jupiter.api.Test;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.support.GenericApplicationContext;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The stop signal the job workers' waits watch (ShutdownTimeTest covers it in a real context). */
class ShutdownSignalTest {

    @Test
    void beforeTheStopItSleepsTheWholeTimeAndChecksPass() throws Exception {
        ShutdownSignal signal = new ShutdownSignal();
        long started = System.nanoTime();
        signal.sleep(100);
        assertThat((System.nanoTime() - started) / 1_000_000).isGreaterThanOrEqualTo(90);
        assertThatCode(signal::check).doesNotThrowAnyException();
        assertThat(signal.stopping()).isFalse();
    }

    @Test
    void theStopEndsASleepAtOnceWithoutInterruptingTheThread() throws Exception {
        ShutdownSignal signal = new ShutdownSignal();
        CompletableFuture<Throwable> sleeper = CompletableFuture.supplyAsync(() -> {
            try {
                signal.sleep(60_000);
                return null;
            } catch (InterruptedException e) {
                return Thread.currentThread().isInterrupted() ? new AssertionError("interrupt flag set") : e;
            }
        });
        Thread.sleep(100);
        long raised = System.nanoTime();
        signal.raise();

        Throwable ended = sleeper.get(5, TimeUnit.SECONDS);
        assertThat((System.nanoTime() - raised) / 1_000_000).isLessThan(1_000);
        assertThat(ended).isInstanceOf(ShutdownSignal.Stopping.class).hasMessage("server shutting down");
        assertThatThrownBy(signal::check).isInstanceOf(ShutdownSignal.Stopping.class);
        assertThatThrownBy(() -> signal.sleep(0)).isInstanceOf(ShutdownSignal.Stopping.class);
    }

    @Test
    void wakeUpsRunOnceAndLateOnesAtOnce() {
        ShutdownSignal signal = new ShutdownSignal();
        AtomicInteger early = new AtomicInteger();
        AtomicInteger late = new AtomicInteger();
        signal.onClose(early::incrementAndGet);
        signal.onClose(() -> {
            throw new IllegalStateException("a broken wake-up does not stop the others");
        });
        signal.onClose(early::incrementAndGet);

        signal.raise();
        signal.raise();
        signal.onClose(late::incrementAndGet);

        assertThat(early.get()).isEqualTo(2);
        assertThat(late.get()).isEqualTo(1);
    }

    @Test
    void onlyItsOwnContextsCloseCounts() {
        ShutdownSignal signal = new ShutdownSignal();
        try (GenericApplicationContext own = new GenericApplicationContext();
             GenericApplicationContext child = new GenericApplicationContext()) {
            signal.setApplicationContext(own);
            // the management server's child context publishes its close to the parent's listeners too
            signal.onApplicationEvent(new ContextClosedEvent(child));
            assertThat(signal.stopping()).isFalse();
            signal.onApplicationEvent(new ContextClosedEvent(own));
            assertThat(signal.stopping()).isTrue();
        }
    }

    @Test
    void restoringTheInterruptSkipsTheSignalsOwnException() {
        ShutdownSignal.restoreInterrupt(new ShutdownSignal.Stopping());
        assertThat(Thread.interrupted()).isFalse();
        ShutdownSignal.restoreInterrupt(new InterruptedException("a real interrupt"));
        assertThat(Thread.interrupted()).isTrue();   // also clears it again for the next test
    }
}
