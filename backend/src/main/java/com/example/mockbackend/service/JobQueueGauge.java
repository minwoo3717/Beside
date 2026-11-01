package com.example.mockbackend.service;

import com.example.mockbackend.domain.JobStatus;
import com.example.mockbackend.repository.JobRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Gauge {@code beside.jobs.queue} (state = pending | processing, docs/METRICS.md §5): how many jobs wait for and
 * occupy the worker. The counts are read from the database every metrics.queue.refresh-ms (5 s) and kept here, so a
 * Prometheus scrape (or several dashboards) never runs a query. Between refreshes the value can be up to 5 s old.
 */
@Component
public class JobQueueGauge {
    private final JobRepository jobs;
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong processing = new AtomicLong();
    private final Logger log = LoggerFactory.getLogger(JobQueueGauge.class);

    public JobQueueGauge(JobRepository jobs, MeterRegistry registry) {
        this.jobs = jobs;
        register(registry, "pending", pending);
        register(registry, "processing", processing);
    }

    @Scheduled(fixedDelayString = "${metrics.queue.refresh-ms:5000}")
    public void refresh() {
        try {
            pending.set(jobs.countByStatus(JobStatus.PENDING));
            processing.set(jobs.countByStatus(JobStatus.PROCESSING));
        } catch (RuntimeException e) {
            // The database closes before the scheduler stops at shutdown; keep the last values.
            log.debug("Queue gauge not refreshed: {}", e.toString());
        }
    }

    private static void register(MeterRegistry registry, String state, AtomicLong value) {
        Gauge.builder("beside.jobs.queue", value, AtomicLong::get)
                .description("Jobs per state, refreshed every metrics.queue.refresh-ms")
                .tag("state", state)
                .strongReference(true)
                .register(registry);
    }
}
