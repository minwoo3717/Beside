package com.example.mockbackend.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The slot line keeps the place taken by {@link InferenceSlots#join} (at dispatch, before the cutout). Review finding
 * 2026-11-25: the place used to be taken only after the cutout, so a retry whose cutout was reused got ahead of new jobs
 * accepted before it, against the accepted-or-retried order of JobQueue and QueueEstimator.
 */
class InferenceSlotsTest {
    private final ExecutorService threads = Executors.newCachedThreadPool();

    @AfterEach
    void stop() {
        threads.shutdownNow();
    }

    @Test
    void aLaterPlaceWaitsWhileAnEarlierOneIsStillInItsCutoutEvenWithTheSlotFree() throws Exception {
        InferenceSlots slots = new InferenceSlots(1, 20);
        Object newJob = slots.join();       // accepted first, still in its cutout
        Object retry = slots.join();        // retried after it, cutout reused: asks for the slot at once
        Future<Boolean> retryGotSlot = threads.submit(() -> slots.acquire(retry, () -> true));

        assertThatThrownBy(() -> retryGotSlot.get(300, TimeUnit.MILLISECONDS))
                .as("the free slot is kept for the earlier place").isInstanceOf(TimeoutException.class);

        assertThat(slots.acquire(newJob, () -> true)).isTrue();
        assertThat(slots.waiting()).isEqualTo(1);
        slots.release();
        assertThat(retryGotSlot.get(5, TimeUnit.SECONDS)).isTrue();
        assertThat(slots.waiting()).isZero();
    }

    @Test
    void aPlaceGivenBackWithoutASlotLetsTheNextOneGo() throws Exception {
        InferenceSlots slots = new InferenceSlots(1, 20);
        Object failedCutout = slots.join();
        Object next = slots.join();
        Future<Boolean> nextGotSlot = threads.submit(() -> slots.acquire(next, () -> true));

        slots.leave(failedCutout);

        assertThat(nextGotSlot.get(5, TimeUnit.SECONDS)).isTrue();
        assertThat(slots.waiting()).isZero();
    }

    @Test
    void leaveAfterTheSlotWasTakenOrTheWaiterGaveUpChangesNothing() throws Exception {
        InferenceSlots slots = new InferenceSlots(1, 20);
        Object first = slots.join();
        assertThat(slots.acquire(first, () -> true)).isTrue();
        Object canceled = slots.join();
        assertThat(slots.acquire(canceled, () -> false)).as("no longer wanted: out at once").isFalse();
        assertThat(slots.waiting()).isZero();

        slots.leave(first);
        slots.leave(canceled);
        Object later = slots.join();
        Future<Boolean> laterGotSlot = threads.submit(() -> slots.acquire(later, () -> true));
        assertThatThrownBy(() -> laterGotSlot.get(200, TimeUnit.MILLISECONDS))
                .as("the slot is still held by the first job").isInstanceOf(TimeoutException.class);
        slots.release();
        assertThat(laterGotSlot.get(5, TimeUnit.SECONDS)).isTrue();
    }
}
