package com.example.mockbackend.service;

import com.example.mockbackend.domain.JobStatus;
import com.example.mockbackend.repository.JobRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.function.ToLongFunction;

/**
 * Gauge {@code beside.jobs.queue} (state = pending | processing, docs/METRICS.md §5): how many jobs wait for and
 * occupy the worker. The counts are read from the database every metrics.queue.refresh-ms (5 s) and kept here, so a
 * Prometheus scrape (or several dashboards) never runs a query. Between refreshes the value can be up to 5 s old.
 * <p>
 * Both counts of one refresh are published together. When a count fails, both gauges read NaN (no number in the
 * scrape) until a refresh succeeds again, so a dashboard shows a gap instead of an old number that looks fine. The first
 * failure of a streak is logged at WARN; while the application context is closing (the database closes before the
 * scheduler stops) failures are only DEBUG.
 */
@Component
public class JobQueueGauge {
    private final JobRepository jobs;
    private final Logger log = LoggerFactory.getLogger(JobQueueGauge.class);

    /** The counts of the last successful refresh, both from the same refresh. */
    private volatile Counts counts = new Counts(0, 0);
    /** True from a failed refresh until the next successful one: the gauges read NaN meanwhile. */
    private volatile boolean stale;
    private volatile boolean closing;

    public JobQueueGauge(JobRepository jobs, MeterRegistry registry) {
        this.jobs = jobs;
        register(registry, "pending", Counts::pending);
        register(registry, "processing", Counts::processing);
    }

    @Scheduled(fixedDelayString = "${metrics.queue.refresh-ms:5000}")
    public void refresh() {
        Counts now;
        try {
            now = new Counts(jobs.countByStatus(JobStatus.PENDING), jobs.countByStatus(JobStatus.PROCESSING));
        } catch (RuntimeException e) {
            if (closing) {
                log.debug("Queue gauge not refreshed while shutting down: {}", e.toString());
            } else if (!stale) {
                log.warn("Queue gauge not refreshed, beside_jobs_queue reads NaN until a count succeeds: {}", e.toString());
            } else {
                log.debug("Queue gauge still not refreshed: {}", e.toString());
            }
            stale = true;
            return;
        }
        counts = now;
        if (stale) {
            stale = false;
            if (!closing) {
                log.info("Queue gauge refreshed again");
            }
        }
    }

    @EventListener(ContextClosedEvent.class)
    public void contextClosing() {
        closing = true;
    }

    private void register(MeterRegistry registry, String state, ToLongFunction<Counts> count) {
        Gauge.builder("beside.jobs.queue", this, gauge -> gauge.stale ? Double.NaN : count.applyAsLong(gauge.counts))
                .description("Jobs per state, refreshed every metrics.queue.refresh-ms; NaN while the counts fail")
                .tag("state", state)
                .strongReference(true)
                .register(registry);
    }

    private record Counts(long pending, long processing) {
    }
}
