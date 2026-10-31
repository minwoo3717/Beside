package com.example.mockbackend.service;

import com.example.mockbackend.domain.Job;
import com.example.mockbackend.domain.JobStatus;
import com.example.mockbackend.repository.JobQueueQueries;
import com.example.mockbackend.repository.JobRepository;
import com.example.mockbackend.repository.JobRunRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * Where a waiting job stands and roughly how long it has left: JobResponse.queue and JobResponse.estimate
 * (docs/api/openapi.yaml JobQueue, JobEstimate). One GPU runs one job at a time (inference.max-concurrency=1) and the
 * others wait as PENDING in the order they were accepted or retried (RealJobWorker's fair slot), so:
 * <ul>
 *   <li>ahead (PENDING only) = PENDING jobs queued before this one (same queuedAt: smaller id first) + jobs PROCESSING.</li>
 *   <li>median = processingMs median of the latest {@value #RECENT_RUNS} COMPLETED, non-passthrough runs of this worker
 *       type (job_runs). Cached for 60 s and dropped whenever a run completes. No such run: no estimate (null).</li>
 *   <li>PENDING: median × (ahead + 1) − the time the running jobs have already spent, each counted up to the median, so
 *       an overrunning job never makes the wait shorter than the jobs still to come. PROCESSING: median − its own
 *       processing time, at least 0.</li>
 *   <li>reason (PENDING only): {@value #INFERENCE_OFFLINE} while the real profile's InferenceHealthMonitor does not read
 *       UP (its last reading, no network call); null otherwise and always in mock, which has no monitor.</li>
 * </ul>
 * One-GPU arithmetic on purpose: with max-concurrency &gt; 1 the estimate is pessimistic (nothing is divided). The mock
 * worker gets the same numbers although it really runs jobs side by side, so the app can show the queue without a GPU.
 * Cost per response: nothing for finished jobs, the cached median for PROCESSING, one read-only transaction with two
 * small queries for PENDING (JobQueueQueries).
 */
@Component
public class QueueEstimator {
    public static final String INFERENCE_OFFLINE = "INFERENCE_OFFLINE";
    static final int RECENT_RUNS = 20;
    static final Duration CACHE_FOR = Duration.ofSeconds(60);

    /** JobResponse.queue. */
    public record Position(int ahead, String reason) {
    }

    /** JobResponse.estimate. */
    public record Estimate(Long remainingMs, int basedOnRuns) {
    }

    /** Both, each null when it does not apply. */
    public record Outlook(Position queue, Estimate estimate) {
        static final Outlook NONE = new Outlook(null, null);
    }

    private record Median(long ms, int runs, Instant computedAt) {
    }

    private final JobRepository jobRepository;
    private final JobRunRepository jobRunRepository;
    private final String workerType;
    private final BooleanSupplier inferenceReady;
    private final Clock clock;
    private final AtomicLong completedRuns = new AtomicLong();
    private volatile Median cached;

    @Autowired
    public QueueEstimator(JobRepository jobRepository, JobRunRepository jobRunRepository, JobWorker jobWorker,
                          ObjectProvider<InferenceHealthMonitor> healthMonitor) {
        this(jobRepository, jobRunRepository, jobWorker.type(), () -> {
            InferenceHealthMonitor monitor = healthMonitor.getIfAvailable();
            return monitor == null || monitor.current().ready();
        }, Clock.systemUTC());
    }

    /** {@code inferenceReady}: whether the GPU inference service is up (always true without one). */
    public QueueEstimator(JobRepository jobRepository, JobRunRepository jobRunRepository, String workerType,
                          BooleanSupplier inferenceReady, Clock clock) {
        this.jobRepository = jobRepository;
        this.jobRunRepository = jobRunRepository;
        this.workerType = workerType;
        this.inferenceReady = inferenceReady;
        this.clock = clock;
    }

    /** The queue position (PENDING) and the estimate (PENDING, PROCESSING) of {@code job} now. */
    public Outlook outlook(Job job) {
        JobStatus status = job.getStatus();
        if (status != JobStatus.PENDING && status != JobStatus.PROCESSING) {
            return Outlook.NONE;
        }
        Instant now = clock.instant();
        Median median = median(now);
        if (status == JobStatus.PROCESSING) {
            Estimate estimate = median.runs() == 0 ? null
                    : new Estimate(Math.max(0, median.ms() - elapsedMs(job.getProcessingStartedAt(), now)), median.runs());
            return new Outlook(null, estimate);
        }
        Instant queuedAt = job.getQueuedAt() != null ? job.getQueuedAt() : job.getCreatedAt();
        JobQueueQueries.QueueSnapshot snapshot = jobRepository.queueSnapshot(queuedAt, job.getId());
        List<Instant> running = snapshot.processingStartedAt();
        int ahead = (int) Math.min(Integer.MAX_VALUE, snapshot.pendingBefore() + running.size());
        Position position = new Position(ahead, inferenceReady.getAsBoolean() ? null : INFERENCE_OFFLINE);
        if (median.runs() == 0) {
            return new Outlook(position, null);
        }
        long spent = running.stream().mapToLong(started -> Math.min(elapsedMs(started, now), median.ms())).sum();
        long remaining = Math.max(0, median.ms() * (ahead + 1L) - spent);
        return new Outlook(position, new Estimate(remaining, median.runs()));
    }

    /** A completed run changes the median: drop the cached one so the next estimate includes it. */
    @EventListener
    public void onJobFinished(JobFinishedEvent event) {
        if (event.status() == JobStatus.COMPLETED) {
            completedRuns.incrementAndGet();
            cached = null;
        }
    }

    private Median median(Instant now) {
        Median current = cached;
        if (current != null && now.isBefore(current.computedAt().plus(CACHE_FOR))) {
            return current;
        }
        long seen = completedRuns.get();
        List<Long> recent = jobRunRepository.findRecentProcessingMs(workerType, JobStatus.COMPLETED,
                PageRequest.of(0, RECENT_RUNS));
        Median fresh = new Median(medianOf(recent), recent.size(), now);
        if (completedRuns.get() == seen) { // a run completed during the query: do not keep a median without it
            cached = fresh;
        }
        return fresh;
    }

    /** Middle value; the mean of the middle two for an even count; 0 for none. */
    private static long medianOf(List<Long> values) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 1 ? sorted.get(middle) : (sorted.get(middle - 1) + sorted.get(middle)) / 2;
    }

    private static long elapsedMs(Instant from, Instant now) {
        return from == null ? 0 : Math.max(0, Duration.between(from, now).toMillis());
    }
}
