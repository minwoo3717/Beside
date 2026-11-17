package com.example.mockbackend.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.mockbackend.domain.Job;
import com.example.mockbackend.domain.JobRun;
import com.example.mockbackend.domain.JobStatus;
import com.example.mockbackend.domain.RunDetails;
import com.example.mockbackend.repository.JobRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.CountAtBucket;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Operational job meters (docs/METRICS.md §5) on a plain SimpleMeterRegistry: one finished run → one count in
 * beside.jobs.finished with its result, error code, worker and passthrough tags, and the queue / processing / total
 * timers in seconds with the SLO buckets. The queue gauge reads two counts every refresh, never per scrape, and shows
 * NaN rather than an old number while the counts fail.
 */
class JobMetricsTest {
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final JobMetrics metrics = new JobMetrics(registry);

    @Test
    void completedRunCountsAndRecordsTheThreeTimers() {
        metrics.onJobFinished(new JobFinishedEvent("a", 1, JobStatus.COMPLETED, null, 7_500L, "real", "m1", false,
                2_000L, 5_500L));

        assertThat(finished("COMPLETED", "none", "real", "false")).isEqualTo(1.0);
        Timer queued = timer("beside.job.queued", "real", "COMPLETED", "false");
        Timer processing = timer("beside.job.processing", "real", "COMPLETED", "false");
        Timer total = timer("beside.job.total", "real", "COMPLETED", "false");
        assertThat(queued.count()).isEqualTo(1);
        assertThat(queued.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(2_000.0);
        assertThat(processing.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(5_500.0);
        assertThat(total.totalTime(TimeUnit.SECONDS)).isEqualTo(7.5);
        // SLO buckets 1 s … 1800 s: 5.5 s lands in the 10 s bucket and above, not in 5 s.
        assertThat(Arrays.stream(processing.takeSnapshot().histogramCounts()).mapToDouble(b -> b.bucket(TimeUnit.SECONDS)))
                .containsExactly(1.0, 5.0, 10.0, 30.0, 60.0, 120.0, 300.0, 600.0, 1800.0);
        assertThat(bucket(processing, 5)).isEqualTo(0);
        assertThat(bucket(processing, 10)).isEqualTo(1);
    }

    @Test
    void failedRunIsCountedWithItsErrorCode() {
        metrics.onJobFinished(new JobFinishedEvent("b", 2, JobStatus.FAILED, "INFERENCE_TIMEOUT", 600_000L, "real", null,
                false, 10L, 599_990L));
        metrics.onJobFinished(new JobFinishedEvent("c", 1, JobStatus.FAILED, "INFERENCE_TIMEOUT", 1_000L, "real", null,
                false, 10L, 990L));

        assertThat(finished("FAILED", "INFERENCE_TIMEOUT", "real", "false")).isEqualTo(2.0);
        assertThat(registry.find("beside.jobs.finished").tag("result", "COMPLETED").counter()).isNull();
        assertThat(timer("beside.job.total", "real", "FAILED", "false").count()).isEqualTo(2);
    }

    @Test
    void passthroughRunIsTaggedSoDashboardsCanLeaveItOut() {
        metrics.onJobFinished(new JobFinishedEvent("d", 1, JobStatus.COMPLETED, null, 900L, "real", null, true,
                100L, 800L));

        assertThat(finished("COMPLETED", "none", "real", "true")).isEqualTo(1.0);
        assertThat(registry.find("beside.jobs.finished").tag("passthrough", "false").counter()).isNull();
        assertThat(timer("beside.job.processing", "real", "COMPLETED", "true").count()).isEqualTo(1);
    }

    @Test
    void aRunThatNeverReachedProcessingRecordsNoProcessingTime() {
        // Failed while PENDING (e.g. INFERENCE_UNAVAILABLE after the offline wait): processingMs is null.
        metrics.onJobFinished(new JobFinishedEvent("e", 1, JobStatus.FAILED, "INFERENCE_UNAVAILABLE", 30_000L, "real",
                null, false, 30_000L, null));

        assertThat(timer("beside.job.queued", "real", "FAILED", "false").count()).isEqualTo(1);
        assertThat(registry.find("beside.job.processing").timer()).isNull();
    }

    @Test
    void anOddEventNeverThrowsIntoTheFinisher() {
        // The listener runs inside JobFinisher: a throw would skip the other listeners (webhook).
        assertThatCode(() -> metrics.onJobFinished(new JobFinishedEvent(null, 1, null, null, null, null, null, false)))
                .doesNotThrowAnyException();
        assertThat(finished("UNKNOWN", "none", "unknown", "false")).isEqualTo(1.0);
    }

    @Test
    void createdAndRetriedAreCounted() {
        metrics.jobCreated();
        metrics.jobCreated();
        metrics.jobRetried();

        assertThat(registry.get("beside.jobs.created").counter().count()).isEqualTo(2.0);
        assertThat(registry.get("beside.jobs.retried").counter().count()).isEqualTo(1.0);
    }

    @Test
    void finishedEventCarriesTheMeasuredQueueAndProcessingTimes() {
        Instant queuedAt = Instant.parse("2026-11-01T00:00:00Z");
        Job job = new Job();
        job.setId("job-1");
        job.setAttempt(1);
        job.setCreatedAt(queuedAt);
        job.setQueuedAt(queuedAt);
        job.setProcessingStartedAt(queuedAt.plusMillis(1_500));
        job.setFinishedAt(queuedAt.plusMillis(4_000));
        job.setStatus(JobStatus.COMPLETED);

        JobFinishedEvent event = JobFinishedEvent.of(JobRun.of(job, job.getFinishedAt(), "real",
                new RunDetails(null, null, null, null, null, null, null, true, null)));

        assertThat(event.queuedMs()).isEqualTo(1_500L);
        assertThat(event.processingMs()).isEqualTo(2_500L);
        assertThat(event.totalMs()).isEqualTo(4_000L);
        assertThat(event.passthrough()).isTrue();
        assertThat(event.workerType()).isEqualTo("real");
    }

    @Test
    void queueGaugeShowsTheCountsOfTheLastRefresh() {
        JobRepository jobs = mock(JobRepository.class);
        JobQueueGauge gauge = new JobQueueGauge(jobs, registry);
        assertThat(queue("pending")).isEqualTo(0.0);

        when(jobs.countByStatus(JobStatus.PENDING)).thenReturn(9L);
        when(jobs.countByStatus(JobStatus.PROCESSING)).thenReturn(1L);
        gauge.refresh();
        assertThat(queue("pending")).isEqualTo(9.0);
        assertThat(queue("processing")).isEqualTo(1.0);
    }

    @Test
    void aFailedRefreshShowsNoNumberUntilACountSucceedsAndWarnsOncePerStreak() {
        JobRepository jobs = mock(JobRepository.class);
        JobQueueGauge gauge = new JobQueueGauge(jobs, registry);
        when(jobs.countByStatus(JobStatus.PENDING)).thenReturn(9L);
        when(jobs.countByStatus(JobStatus.PROCESSING)).thenReturn(1L);
        gauge.refresh();

        try (LogCapture log = new LogCapture(JobQueueGauge.class)) {
            // The PENDING count works, the PROCESSING count fails: no half-new pair, and no old number that looks fine.
            doReturn(12L).when(jobs).countByStatus(JobStatus.PENDING);
            doThrow(new IllegalStateException("pool exhausted")).when(jobs).countByStatus(JobStatus.PROCESSING);
            assertThatCode(gauge::refresh).doesNotThrowAnyException();
            assertThat(queue("pending")).isNaN();
            assertThat(queue("processing")).isNaN();
            assertThatCode(gauge::refresh).doesNotThrowAnyException();
            assertThat(log.at(Level.WARN)).singleElement().asString().contains("pool exhausted");

            doReturn(2L).when(jobs).countByStatus(JobStatus.PROCESSING);
            gauge.refresh();
            assertThat(queue("pending")).isEqualTo(12.0);
            assertThat(queue("processing")).isEqualTo(2.0);

            // A new streak warns again.
            doThrow(new IllegalStateException("pool exhausted again")).when(jobs).countByStatus(JobStatus.PENDING);
            gauge.refresh();
            assertThat(queue("processing")).isNaN();
            assertThat(log.at(Level.WARN)).hasSize(2).last().asString().contains("pool exhausted again");
        }
    }

    @Test
    void aFailedRefreshWhileTheContextClosesIsOnlyDebug() {
        // The database closes before the scheduler stops at shutdown: nothing to warn anyone about.
        JobRepository jobs = mock(JobRepository.class);
        JobQueueGauge gauge = new JobQueueGauge(jobs, registry);
        try (LogCapture log = new LogCapture(JobQueueGauge.class)) {
            gauge.contextClosing();
            when(jobs.countByStatus(JobStatus.PENDING)).thenThrow(new IllegalStateException("closed"));
            assertThatCode(gauge::refresh).doesNotThrowAnyException();
            assertThat(queue("pending")).isNaN();
            assertThat(log.at(Level.WARN)).isEmpty();
            assertThat(log.at(Level.DEBUG)).singleElement().asString().contains("closed");
        }
    }

    /** Collects one class's log lines at DEBUG and up while open, then restores its level. */
    private static final class LogCapture implements AutoCloseable {
        private final Logger logger;
        private final Level levelBefore;
        private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

        LogCapture(Class<?> type) {
            logger = (Logger) LoggerFactory.getLogger(type);
            levelBefore = logger.getLevel();
            logger.setLevel(Level.DEBUG);
            appender.start();
            logger.addAppender(appender);
        }

        List<String> at(Level level) {
            return appender.list.stream().filter(e -> e.getLevel() == level).map(ILoggingEvent::getFormattedMessage).toList();
        }

        @Override
        public void close() {
            logger.detachAppender(appender);
            logger.setLevel(levelBefore);
        }
    }

    private double finished(String result, String code, String worker, String passthrough) {
        Counter counter = registry.find("beside.jobs.finished")
                .tags("result", result, "code", code, "worker", worker, "passthrough", passthrough).counter();
        return counter == null ? 0 : counter.count();
    }

    private Timer timer(String name, String worker, String result, String passthrough) {
        return registry.get(name).tags("worker", worker, "result", result, "passthrough", passthrough).timer();
    }

    private double queue(String state) {
        return registry.get("beside.jobs.queue").tag("state", state).gauge().value();
    }

    private static double bucket(Timer timer, double seconds) {
        for (CountAtBucket bucket : timer.takeSnapshot().histogramCounts()) {
            if (bucket.bucket(TimeUnit.SECONDS) == seconds) {
                return bucket.count();
            }
        }
        throw new AssertionError("no " + seconds + " s bucket");
    }
}
