package com.example.mockbackend;

import com.example.mockbackend.service.QueueClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** queuedAt never ties, so the queue order is the order of submission even on a coarse clock. */
class QueueClockTest {

    @Test
    void everyInstantIsLaterThanThePreviousOne() {
        QueueClock clock = new QueueClock();
        Instant previous = clock.next();
        for (int i = 0; i < 5_000; i++) {
            Instant next = clock.next();
            assertThat(next).isAfter(previous);
            previous = next;
        }
        assertThat(Duration.between(Instant.now(), previous).abs()).as("ties step 1 ms, not more")
                .isLessThan(Duration.ofSeconds(10));
    }

    @Test
    void concurrentCallersNeverGetTheSameInstant() throws InterruptedException {
        QueueClock clock = new QueueClock();
        ConcurrentLinkedQueue<Instant> seen = new ConcurrentLinkedQueue<>();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        for (int i = 0; i < 2_000; i++) {
            pool.execute(() -> seen.add(clock.next()));
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        List<Instant> all = new ArrayList<>(seen);
        assertThat(all).hasSize(2_000).doesNotHaveDuplicates();
    }
}
