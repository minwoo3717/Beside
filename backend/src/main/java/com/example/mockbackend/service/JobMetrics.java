package com.example.mockbackend.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Operational job meters (docs/METRICS.md §5), scraped by Prometheus on the management port:
 * <ul>
 *   <li>{@code beside.jobs.finished} (result, code, worker, passthrough): one per finished run, from JobFinishedEvent.</li>
 *   <li>{@code beside.job.queued|processing|total} (worker, result, passthrough): the run's queuedMs, processingMs and
 *       totalMs (the same numbers as job_runs and events.jsonl) as timers with fixed SLO buckets.</li>
 *   <li>{@code beside.jobs.created}, {@code beside.jobs.retried}: JobServiceImpl calls these after a dispatch succeeded.</li>
 * </ul>
 * The finish listener runs inside JobFinisher on the worker thread, so it never throws: a failure here would skip the
 * other listeners (the webhook). Metrics never change a job.
 */
@Component
public class JobMetrics {
    /** Generation-time buckets: the app gives up after 600 s; 1800 s covers the offline wait of real,prod. */
    static final Duration[] JOB_SLO = {Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(10),
            Duration.ofSeconds(30), Duration.ofSeconds(60), Duration.ofSeconds(120), Duration.ofSeconds(300),
            Duration.ofSeconds(600), Duration.ofSeconds(1800)};

    private final MeterRegistry registry;
    private final Counter created;
    private final Counter retried;
    private final Logger log = LoggerFactory.getLogger(JobMetrics.class);

    public JobMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.created = Counter.builder("beside.jobs.created")
                .description("Jobs accepted and handed to the worker (v1 and v0; Idempotency-Key replays not counted)")
                .register(registry);
        this.retried = Counter.builder("beside.jobs.retried")
                .description("Retries accepted and handed to the worker")
                .register(registry);
    }

    public void jobCreated() {
        created.increment();
    }

    public void jobRetried() {
        retried.increment();
    }

    @EventListener
    public void onJobFinished(JobFinishedEvent event) {
        try {
            String result = event.status() == null ? "UNKNOWN" : event.status().name();
            String code = event.errorCode() == null || event.errorCode().isBlank() ? "none" : event.errorCode();
            String worker = event.workerType() == null || event.workerType().isBlank() ? "unknown" : event.workerType();
            String passthrough = String.valueOf(event.passthrough());
            Counter.builder("beside.jobs.finished")
                    .description("Finished runs (attempts) of jobs")
                    .tags("result", result, "code", code, "worker", worker, "passthrough", passthrough)
                    .register(registry)
                    .increment();
            Tags tags = Tags.of("worker", worker, "result", result, "passthrough", passthrough);
            record("beside.job.queued", "PENDING time of a run (queuedMs)", tags, event.queuedMs());
            record("beside.job.processing", "PROCESSING time of a run (processingMs)", tags, event.processingMs());
            record("beside.job.total", "Accept (or retry) to finish (totalMs)", tags, event.totalMs());
        } catch (RuntimeException e) {
            log.warn("jobId={} attempt={} not counted in the job metrics: {}", event.jobId(), event.attempt(), e.toString());
        }
    }

    private void record(String name, String description, Tags tags, Long millis) {
        if (millis == null || millis < 0) {
            return;
        }
        Timer.builder(name)
                .description(description)
                .tags(tags)
                .serviceLevelObjectives(JOB_SLO)
                .register(registry)
                .record(millis, TimeUnit.MILLISECONDS);
    }
}
