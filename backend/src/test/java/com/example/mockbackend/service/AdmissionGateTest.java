package com.example.mockbackend.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.mockbackend.exception.ApiException;
import com.example.mockbackend.exception.ErrorCode;
import com.example.mockbackend.repository.JobRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The queue cap's counting (REVIEW_CHECKLIST #21, proposal before the contract meeting) without Spring or a database:
 * PENDING rows come from a list of queuedAt instants, the clock is set by the test. Off means no query at all; on, the
 * rows plus the places held by requests being admitted never pass the cap, also with many threads admitting and
 * closing at once, and every place comes back exactly once.
 */
class AdmissionGateTest {
    private static final int RETRY_AFTER = 45;
    private static final long STALE_AFTER_MS = Duration.ofMinutes(10).toMillis();

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-20T03:00:00Z"));
    /** queuedAt of the PENDING rows in the fake job table. */
    private final List<Instant> pending = new CopyOnWriteArrayList<>();
    private final AtomicInteger countQueries = new AtomicInteger();
    private final AtomicInteger staleQueries = new AtomicInteger();
    private final List<AdmissionGate.Op> refused = new CopyOnWriteArrayList<>();

    private final AdmissionGate.PendingCounts counts = new AdmissionGate.PendingCounts() {
        @Override
        public long queuedAfter(Instant cutoff) {
            countQueries.incrementAndGet();
            return pending.stream().filter(queuedAt -> queuedAt.isAfter(cutoff)).count();
        }

        @Override
        public long queuedUpTo(Instant cutoff) {
            staleQueries.incrementAndGet();
            return pending.stream().filter(queuedAt -> !queuedAt.isAfter(cutoff)).count();
        }
    };

    private final Logger gateLog = (Logger) LoggerFactory.getLogger(AdmissionGate.class);
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();

    @BeforeEach
    void captureLog() {
        logged.start();
        gateLog.addAppender(logged);
    }

    @AfterEach
    void releaseLog() {
        gateLog.detachAppender(logged);
    }

    @Test
    void capZeroAdmitsEverythingWithoutAQuery() {
        AdmissionGate gate = gate(0);
        for (int i = 0; i < 100; i++) {
            gate.admit(AdmissionGate.Op.CREATE).close();
            gate.admitRetry("job-" + i, () -> {
                throw new AssertionError("an open gate never refuses");
            }).close();
        }
        assertThat(countQueries).hasValue(0);
        assertThat(staleQueries).hasValue(0);
        assertThat(gate.reserved()).isZero();
        assertThat(logged.list).extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly("Queue admission: off (admission.max-pending=0)");
    }

    @Test
    void belowTheCapAdmitsAndAtTheCapRefusesWithTheConfiguredRetryAfter() {
        AdmissionGate gate = gate(3);
        addPending(2, clock.instant());

        AdmissionGate.Permit third = gate.admit(AdmissionGate.Op.CREATE); // 2 rows + 0 reserved < 3
        assertThat(gate.reserved()).isEqualTo(1);

        // 2 rows + 1 reserved = 3: full.
        assertThatThrownBy(() -> gate.admit(AdmissionGate.Op.CREATE)).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getCode()).isEqualTo(ErrorCode.QUEUE_FULL);
            assertThat(e.getStatus().value()).isEqualTo(503);
            assertThat(e.getRetryAfterSeconds()).isEqualTo(RETRY_AFTER);
            assertThat(e.getJobId()).isNull();
        });
        assertThat(refused).containsExactly(AdmissionGate.Op.CREATE);
        assertThat(lines(Level.INFO)).contains(
                "Queue full: refused op=create jobId=- pending=2 reserved=1 cap=3 retryAfter=45s");

        // The third request's row is committed (counted twice until its permit closes), then the place goes back.
        addPending(1, clock.instant());
        third.close();
        assertThat(gate.reserved()).isZero();
        assertThatThrownBy(() -> gate.admit(AdmissionGate.Op.CREATE)).isInstanceOf(ApiException.class);
        pending.remove(0); // one job left PENDING
        assertThatCode(() -> gate.admit(AdmissionGate.Op.CREATE).close()).doesNotThrowAnyException();
    }

    @Test
    void fiftyRequestsAtOnceGetExactlyAsManyPlacesAsTheCap() throws Exception {
        AdmissionGate gate = gate(5);
        int requests = 50;
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        CountDownLatch go = new CountDownLatch(1);
        List<AdmissionGate.Permit> permits = new CopyOnWriteArrayList<>();
        AtomicInteger full = new AtomicInteger();
        try {
            List<Future<?>> done = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                done.add(pool.submit(() -> {
                    go.await();
                    try {
                        permits.add(gate.admit(AdmissionGate.Op.CREATE)); // held: nothing is committed in this test
                    } catch (ApiException e) {
                        assertThat(e.getCode()).isEqualTo(ErrorCode.QUEUE_FULL);
                        full.incrementAndGet();
                    }
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> f : done) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(permits).hasSize(5);
        assertThat(full).hasValue(45);
        assertThat(refused).hasSize(45);
        assertThat(gate.reserved()).isEqualTo(5);
        permits.forEach(AdmissionGate.Permit::close);
        assertThat(gate.reserved()).isZero();
    }

    @Test
    void admittingAndClosingFromManyThreadsNeverPassesTheCapAndEndsWithNothingReserved() throws Exception {
        int cap = 4;
        AdmissionGate gate = gate(cap);
        int threads = 16;
        int rounds = 2_000;
        AtomicInteger inUse = new AtomicInteger();
        AtomicInteger mostInUse = new AtomicInteger();
        AtomicInteger admitted = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<?>> done = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int thread = t;
                done.add(pool.submit(() -> {
                    go.await();
                    for (int i = 0; i < rounds; i++) {
                        AdmissionGate.Permit permit;
                        try {
                            permit = thread % 2 == 0 ? gate.admit(AdmissionGate.Op.CREATE)
                                    : gate.admitRetry("job-" + thread + "-" + i, () -> null);
                        } catch (ApiException full) {
                            continue;
                        }
                        try (permit) {
                            mostInUse.accumulateAndGet(inUse.incrementAndGet(), Math::max);
                            admitted.incrementAndGet();
                            Thread.yield();
                            inUse.decrementAndGet();
                        }
                        permit.close(); // a second close gives nothing back
                    }
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> f : done) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(mostInUse.get()).isBetween(1, cap);
        assertThat(admitted.get()).isPositive();
        assertThat(gate.reserved()).isZero();
        assertThat(admitted.get() + refused.size()).isEqualTo(threads * rounds);
    }

    @Test
    void closingAPermitTwiceGivesItsPlaceBackOnce() {
        AdmissionGate gate = gate(2);
        AdmissionGate.Permit first = gate.admit(AdmissionGate.Op.CREATE);
        AdmissionGate.Permit second = gate.admit(AdmissionGate.Op.CREATE);
        first.close();
        first.close();
        assertThat(gate.reserved()).isEqualTo(1);
        AdmissionGate.Permit third = gate.admit(AdmissionGate.Op.CREATE);
        assertThatThrownBy(() -> gate.admit(AdmissionGate.Op.CREATE)).isInstanceOf(ApiException.class);
        second.close();
        third.close();
        assertThat(gate.reserved()).isZero();
    }

    @Test
    void stalePendingRowsAreNotCountedAndAreReportedAtMostOncePerMinute() {
        AdmissionGate gate = gate(2);
        // Three rows a lost worker left PENDING 20 minutes ago (stale-after 10 min) and one waiting now.
        addPending(3, clock.instant().minus(Duration.ofMinutes(20)));
        addPending(1, clock.instant());

        gate.admit(AdmissionGate.Op.CREATE).close(); // 1 counted < 2
        assertThat(lines(Level.WARN)).singleElement().asString()
                .startsWith("Queue admission: 3 PENDING job(s) queued before 2026-11-20T02:50:00Z")
                .contains("admission.stale-after-ms=600000");

        clock.advance(Duration.ofSeconds(59));
        gate.admit(AdmissionGate.Op.CREATE).close();
        assertThat(staleQueries).hasValue(1);
        assertThat(lines(Level.WARN)).hasSize(1);

        clock.advance(Duration.ofSeconds(1));
        gate.admit(AdmissionGate.Op.CREATE).close();
        assertThat(staleQueries).hasValue(2);
        assertThat(lines(Level.WARN)).hasSize(2);

        // A row turns stale with time: the one queued at the start is counted until 10 minutes have passed.
        addPending(1, clock.instant());
        assertThatThrownBy(() -> gate.admit(AdmissionGate.Op.CREATE)).isInstanceOf(ApiException.class);
        clock.advance(Duration.ofMinutes(10));
        assertThatCode(() -> gate.admit(AdmissionGate.Op.CREATE).close()).doesNotThrowAnyException();
    }

    @Test
    void aSecondRetryOfTheSameJobGets409WhileTheFirstHoldsItsPlace() {
        AdmissionGate gate = gate(5);
        AdmissionGate.Permit first = gate.admitRetry("job-1", () -> null);

        assertThatThrownBy(() -> gate.admitRetry("job-1", () -> null)).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getCode()).isEqualTo(ErrorCode.JOB_NOT_FAILED);
            assertThat(e.getStatus().value()).isEqualTo(409);
            assertThat(e.getJobId()).isEqualTo("job-1");
        });
        assertThat(refused).isEmpty();
        assertThatCode(() -> gate.admitRetry("job-2", () -> null).close()).doesNotThrowAnyException();

        first.close();
        assertThatCode(() -> gate.admitRetry("job-1", () -> null).close()).doesNotThrowAnyException();
        assertThat(gate.reserved()).isZero();
    }

    @Test
    void aRefusedRetryLetsTheCallerAnswerFirstAndOtherwiseNamesItsJob() {
        AdmissionGate gate = gate(1);
        addPending(1, clock.instant());
        ApiException requeued = ApiException.conflict(ErrorCode.JOB_NOT_FAILED, "job-1", "already re-queued");

        assertThatThrownBy(() -> gate.admitRetry("job-1", () -> requeued)).isSameAs(requeued);
        assertThat(refused).as("only a 503 is counted").isEmpty();

        assertThatThrownBy(() -> gate.admitRetry("job-2", () -> null)).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getCode()).isEqualTo(ErrorCode.QUEUE_FULL);
            assertThat(e.getJobId()).isEqualTo("job-2");
            assertThat(e.getRetryAfterSeconds()).isEqualTo(RETRY_AFTER);
        });
        assertThat(refused).containsExactly(AdmissionGate.Op.RETRY);
        assertThat(lines(Level.INFO)).contains(
                "Queue full: refused op=retry jobId=job-2 pending=1 reserved=0 cap=1 retryAfter=45s");
        assertThat(gate.reserved()).isZero();
    }

    @Test
    void aWrongSettingStopsTheServerFromStarting() {
        assertThatThrownBy(() -> gate(1, 0, STALE_AFTER_MS)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("admission.retry-after-seconds");
        assertThatThrownBy(() -> gate(-1, RETRY_AFTER, STALE_AFTER_MS)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("admission.max-pending");
        assertThatThrownBy(() -> gate(1, RETRY_AFTER, 0)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("admission.stale-after-ms");
        // Off, the other two values are not used.
        assertThatCode(() -> gate(0, 0, 0)).doesNotThrowAnyException();
    }

    @Test
    void theServerReadsTheSettingsAndLogsThemOnce() {
        JobRepository jobs = mock(JobRepository.class);
        JobMetrics metrics = new JobMetrics(new SimpleMeterRegistry());

        // prod: N 19, offline wait 30 min, /infer timeout 10 min, one inference slot, 8 worker threads (Spring default).
        AdmissionGate prod = new AdmissionGate(jobs, metrics, 19, 165, "", 1_800_000, 600_000, 1, 8);
        assertThat(prod.maxPending()).isEqualTo(19);
        assertThat(prod.retryAfterSeconds()).isEqualTo(165);
        assertThat(prod.staleAfterMs()).as("3 offline waits + 2 x 19 timeouts + 10 min = 8 h").isEqualTo(28_800_000);
        assertThat(new AdmissionGate(jobs, metrics, 19, 165, "", 0, 600_000, 1, 8).staleAfterMs())
                .as("real without the offline wait: 2 x 19 timeouts + 10 min").isEqualTo(23_400_000);
        assertThat(new AdmissionGate(jobs, metrics, 19, 165, " ", 1_800_000, 600_000, 1, 8).staleAfterMs())
                .as("blank is unset").isEqualTo(28_800_000);
        assertThat(new AdmissionGate(jobs, metrics, 19, 165, "5000", 1_800_000, 600_000, 1, 8).staleAfterMs())
                .isEqualTo(5_000);
        assertThat(lines(Level.INFO)).first().isEqualTo(
                "Queue admission: on, max-pending=19 retry-after-seconds=165 stale-after-ms=28800000");
        assertThatThrownBy(() -> new AdmissionGate(jobs, metrics, 19, 165, "10m", 0, 600_000, 1, 8))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("admission.stale-after-ms");
        verifyNoInteractions(jobs); // nothing is counted before the first request
    }

    /**
     * The default of admission.stale-after-ms is the longest a job can legitimately wait as PENDING while the cap
     * holds, so the safety net never drops a job that is only waiting. The real worker starts a job's offline wait when
     * an executor thread takes it, and holds the one inference slot during that wait and during /infer, so with the GPU
     * off the jobs past the 8 worker threads wait a whole offline wait per round of threads, and behind hung /infer calls
     * every job ahead costs up to the timeout twice (upload mode: the upload POST and the GLB download).
     */
    @Test
    void theDefaultStaleAfterIsTheLongestAQueuedJobCanWait() {
        long prod = AdmissionGate.defaultStaleAfterMs(19, 1, 8, 1_800_000, 600_000);
        // GPU off (review of 2026-11-20): jobs 17-19 get a thread after two rounds of 8 have each waited 30 min.
        assertThat(prod).isGreaterThan(Duration.ofMinutes(90).toMillis());
        // Hung /infer: 19 jobs ahead, each holding the slot up to the timeout for the upload and again for the GLB.
        assertThat(prod).isGreaterThan(19 * 2 * 600_000L);
        assertThat(prod).as("the old default, offline wait + 10 min, dropped waiting jobs")
                .isGreaterThan(2_400_000L);
        assertThat(prod).isEqualTo(3 * 1_800_000L + 2 * 19 * 600_000L + AdmissionGate.STALE_SLACK_MS);

        // Two slots share the timeouts; 400 worker threads (load test) take every job at once; 0 and less mean 1.
        assertThat(AdmissionGate.defaultStaleAfterMs(19, 2, 8, 1_800_000, 600_000))
                .isEqualTo(3 * 1_800_000L + 2 * 10 * 600_000L + AdmissionGate.STALE_SLACK_MS);
        assertThat(AdmissionGate.defaultStaleAfterMs(19, 1, 400, 300_000, 120_000))
                .isEqualTo(300_000L + 2 * 19 * 120_000L + AdmissionGate.STALE_SLACK_MS);
        assertThat(AdmissionGate.defaultStaleAfterMs(3, 0, 0, 0, 600_000))
                .isEqualTo(2 * 3 * 600_000L + AdmissionGate.STALE_SLACK_MS);
    }

    /** As Spring binds it: an empty admission.stale-after-ms (an unset compose variable) means the default. */
    @Test
    void anEmptyStaleAfterSettingMeansTheDefaultAndANonNumberStopsTheStart() {
        ApplicationContextRunner server = new ApplicationContextRunner()
                .withBean(JobRepository.class, () -> mock(JobRepository.class))
                .withBean(JobMetrics.class, () -> new JobMetrics(new SimpleMeterRegistry()))
                .withBean(AdmissionGate.class)
                .withPropertyValues("admission.max-pending=19", "inference.offline-wait-ms=1800000",
                        "inference.timeout-ms=600000");

        server.withPropertyValues("admission.stale-after-ms=").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AdmissionGate.class).staleAfterMs()).isEqualTo(28_800_000);
        });
        server.withPropertyValues("admission.stale-after-ms=", "spring.task.execution.pool.core-size=400",
                "inference.max-concurrency=2").run(context ->
                assertThat(context.getBean(AdmissionGate.class).staleAfterMs())
                        .isEqualTo(AdmissionGate.defaultStaleAfterMs(19, 2, 400, 1_800_000, 600_000)));
        server.withPropertyValues("admission.stale-after-ms=900000").run(context ->
                assertThat(context.getBean(AdmissionGate.class).staleAfterMs()).isEqualTo(900_000));
        server.withPropertyValues("admission.stale-after-ms=30m").run(context ->
                assertThat(context).getFailure().rootCause().hasMessageContaining("admission.stale-after-ms"));
    }

    private AdmissionGate gate(int maxPending) {
        return gate(maxPending, RETRY_AFTER, STALE_AFTER_MS);
    }

    private AdmissionGate gate(int maxPending, int retryAfterSeconds, long staleAfterMs) {
        return new AdmissionGate(counts, refused::add, clock, maxPending, retryAfterSeconds, staleAfterMs);
    }

    private void addPending(int rows, Instant queuedAt) {
        for (int i = 0; i < rows; i++) {
            pending.add(queuedAt);
        }
    }

    private List<String> lines(Level level) {
        return logged.list.stream().filter(e -> e.getLevel() == level).map(ILoggingEvent::getFormattedMessage).toList();
    }

    /** A clock the test moves by hand. */
    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        MutableClock(Instant start) {
            this.now = new AtomicReference<>(start);
        }

        void advance(Duration by) {
            now.updateAndGet(t -> t.plus(by));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
