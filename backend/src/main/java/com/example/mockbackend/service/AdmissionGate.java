package com.example.mockbackend.service;

import com.example.mockbackend.domain.JobStatus;
import com.example.mockbackend.exception.ApiException;
import com.example.mockbackend.exception.ErrorCode;
import com.example.mockbackend.repository.JobRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Queue cap (REVIEW_CHECKLIST #21, decided 2026-11-21, API v1.1; off by default and kept off in production until a new
 * Unity build is installed and the 5th load test confirms N): v1 create and retry are
 * refused with 503 QUEUE_FULL and Retry-After while the PENDING jobs plus the requests being admitted reach
 * admission.max-pending. v0 is frozen and never asks.
 * <ul>
 *   <li>A request takes a {@link Permit} before its INSERT (create) or its FAILED → PENDING update (retry) and closes
 *       it once that write is committed. admit and close use this object's monitor, so requests checked at the same
 *       time cannot all pass the same count and the cap is exact. Between the commit and the close a request is counted
 *       twice (row and reservation), which only makes the cap stricter for that moment; it is never under-counted.</li>
 *   <li>The reservation lives inside one request (try-with-resources), so no worker path has to give it back. One JVM
 *       owns the database file (no AUTO_SERVER), so this count plus the database is the whole queue.</li>
 *   <li>PENDING rows queued longer ago than admission.stale-after-ms are not counted: a row whose worker was lost would
 *       otherwise hold a place until the next restart. Unset (or empty), it is {@link #defaultStaleAfterMs}, the longest
 *       a job can legitimately wait as PENDING while the cap holds, so the safety net never drops a job that is only
 *       waiting (a GPU outage, hung /infer calls). A check of their own, once a minute while the cap is on, shows them
 *       as the gauge beside.jobs.pending.stale and in one WARN line when there are any; requests never count them.</li>
 *   <li>Two retries of the same job: while one holds a permit the other gets 409 JOB_NOT_FAILED (it would lose the
 *       conditional update anyway), so a full queue never answers it 503 for a job another retry is re-queuing.</li>
 *   <li>Two creates with the same Idempotency-Key: while one holds a permit the other gets a place too, even over the
 *       cap (only one job comes out of a key), so a resend gets the job as it does without the cap.</li>
 *   <li>max-pending 0 (the default) admits everything without a query: behaviour stays as without the cap.</li>
 * </ul>
 * Every refusal answered 503 is counted (beside.jobs.refused{op}) and logged once here with the numbers it was
 * decided on, so a load test can check pending + reserved ≥ cap for each refusal.
 */
@Component
public class AdmissionGate implements MeterBinder {
    /** How often the stale PENDING rows are counted for the gauge and the WARN line (only while the cap is on). */
    static final long STALE_CHECK_MS = 60_000;
    /** The margin in {@link #defaultStaleAfterMs} (cutout, transfers, cleanup, scheduling). */
    static final long STALE_SLACK_MS = Duration.ofMinutes(10).toMillis();

    private static final Logger log = LoggerFactory.getLogger(AdmissionGate.class);

    /** What asks for a place: the tag of beside.jobs.refused. */
    public enum Op {
        CREATE, RETRY;

        public String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** The two counts the gate reads: the job table in the server, a fake in AdmissionGateTest. */
    interface PendingCounts {
        /** PENDING jobs queued after the cutoff: the ones the cap counts. */
        long queuedAfter(Instant cutoff);

        /** PENDING jobs queued at or before the cutoff: stale, not counted, only reported. */
        long queuedUpTo(Instant cutoff);
    }

    private final PendingCounts counts;
    private final Consumer<Op> onRefused;
    private final Clock clock;
    private final int maxPending;
    private final int retryAfterSeconds;
    private final long staleAfterMs;

    // Guarded by this.
    private int reserved;
    /** Jobs whose retry holds a permit. */
    private final Set<String> retrying = new HashSet<>();
    /** Idempotency-Keys of creates holding a permit, with how many hold one. */
    private final Map<String, Integer> creating = new HashMap<>();

    /** PENDING rows the cap leaves out, as of the last check; NaN while the check fails. */
    private volatile double staleRows;
    private volatile boolean closing;

    /**
     * admission.stale-after-ms is read as text so that an empty value (a compose variable left unset) means the
     * default, as when the setting is absent; anything else must be a whole number of milliseconds. The default is
     * worked out from the worker's settings: the inference slots (inference.max-concurrency), the worker threads
     * (spring.task.execution.pool.core-size, Spring's default 8 with an unbounded queue), the offline wait and the
     * /infer timeout. The fallbacks match the workers' own (InferenceClient: inference.timeout-ms 120000, 2026-11-21
     * REVIEW_CHECKLIST #7), so the gate never assumes a longer wait than a worker allows.
     */
    @Autowired
    public AdmissionGate(JobRepository jobs, JobMetrics metrics,
                         @Value("${admission.max-pending:0}") int maxPending,
                         @Value("${admission.retry-after-seconds:165}") int retryAfterSeconds,
                         @Value("${admission.stale-after-ms:}") String staleAfterMs,
                         @Value("${inference.offline-wait-ms:0}") long offlineWaitMs,
                         @Value("${inference.timeout-ms:120000}") long inferenceTimeoutMs,
                         @Value("${inference.max-concurrency:1}") int inferenceSlots,
                         @Value("${spring.task.execution.pool.core-size:8}") int workerThreads) {
        this(pendingJobs(jobs), metrics::jobRefused, Clock.systemUTC(), maxPending, retryAfterSeconds,
                staleAfterMs == null || staleAfterMs.isBlank()
                        ? defaultStaleAfterMs(maxPending, inferenceSlots, workerThreads, offlineWaitMs, inferenceTimeoutMs)
                        : parseMillis(staleAfterMs));
    }

    /**
     * The longest a job can wait as PENDING while at most {@code maxPending} are queued, plus {@link #STALE_SLACK_MS}:
     * the default of admission.stale-after-ms. The real worker
     * <ul>
     *   <li>starts a job's offline wait when an executor thread takes the job, so with the GPU off each round of
     *       {@code workerThreads} jobs waits a whole offline wait: ⌈(maxPending + slots) / threads⌉ waits for the last
     *       one (the jobs ahead include the ones holding a slot);</li>
     *   <li>holds an inference slot during /infer, up to the timeout for the call and again for the GLB download
     *       (upload mode), so behind hung calls the last job waits up to 2 × ⌈maxPending / slots⌉ timeouts.</li>
     * </ul>
     * The two add up (the offline wait is also spent holding a slot). Values below 1 thread or 1 slot count as 1.
     */
    static long defaultStaleAfterMs(int maxPending, int inferenceSlots, int workerThreads, long offlineWaitMs,
                                    long inferenceTimeoutMs) {
        long slots = Math.max(1, inferenceSlots);
        long threads = Math.max(1, workerThreads);
        long pending = Math.max(0, maxPending);
        long threadRounds = (pending + slots + threads - 1) / threads;
        long slotRounds = (pending + slots - 1) / slots;
        return threadRounds * Math.max(0L, offlineWaitMs) + 2 * slotRounds * Math.max(0L, inferenceTimeoutMs)
                + STALE_SLACK_MS;
    }

    private static long parseMillis(String staleAfterMs) {
        try {
            return Long.parseLong(staleAfterMs.strip());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("admission.stale-after-ms must be a whole number of milliseconds, or unset"
                    + " for the default, got '" + staleAfterMs + "'");
        }
    }

    AdmissionGate(PendingCounts counts, Consumer<Op> onRefused, Clock clock, int maxPending, int retryAfterSeconds,
                  long staleAfterMs) {
        if (maxPending < 0) {
            throw new IllegalStateException("admission.max-pending must be 0 (off) or more, got " + maxPending);
        }
        if (maxPending > 0 && retryAfterSeconds < 1) {
            throw new IllegalStateException("admission.retry-after-seconds must be 1 or more while admission.max-pending"
                    + " is on (Retry-After is at least 1 s in the contract), got " + retryAfterSeconds);
        }
        if (maxPending > 0 && staleAfterMs < 1) {
            throw new IllegalStateException("admission.stale-after-ms must be more than 0 while admission.max-pending is"
                    + " on (leave it unset for the longest a queued job can wait), got " + staleAfterMs);
        }
        this.counts = counts;
        this.onRefused = onRefused;
        this.clock = clock;
        this.maxPending = maxPending;
        this.retryAfterSeconds = retryAfterSeconds;
        this.staleAfterMs = staleAfterMs;
        if (maxPending == 0) {
            log.info("Queue admission: off (admission.max-pending=0)");
        } else {
            log.info("Queue admission: on, max-pending={} retry-after-seconds={} stale-after-ms={}",
                    maxPending, retryAfterSeconds, staleAfterMs);
        }
    }

    /** A place for a v1 create without an Idempotency-Key, or 503 QUEUE_FULL (jobId null). */
    public Permit admit(Op op) {
        return admit(op, null, null, null);
    }

    /**
     * A place for a v1 create, or 503 QUEUE_FULL (jobId null). Close the permit once the job row is committed and its
     * Idempotency-Key linked. While another create with the same key holds a permit (a resend during the first one's
     * photo check), this one gets a place even over the cap: the key's insert-only link lets only one of the two create
     * a job and the other answers with it, so the queue gains one job at most, and each holds its own place until it
     * is done, so nothing is under-counted. When the queue is full, whatever {@code insteadOfQueueFull} returns is
     * thrown instead of the 503 (read outside the lock: the job of a key linked meanwhile).
     */
    public Permit admitCreate(String idempotencyKey, Supplier<? extends RuntimeException> insteadOfQueueFull) {
        return admit(Op.CREATE, null, idempotencyKey, insteadOfQueueFull);
    }

    /**
     * A place for the retry of jobId, or: 409 JOB_NOT_FAILED while another retry of the same job holds a permit;
     * otherwise, when the queue is full, whatever {@code insteadOfQueueFull} returns (read outside the lock, for a job
     * another retry already re-queued), or 503 QUEUE_FULL naming the job when it returns null.
     */
    public Permit admitRetry(String jobId, Supplier<? extends RuntimeException> insteadOfQueueFull) {
        return admit(Op.RETRY, jobId, null, insteadOfQueueFull);
    }

    private Permit admit(Op op, String jobId, String key, Supplier<? extends RuntimeException> insteadOfQueueFull) {
        if (maxPending == 0) {
            return Permit.NONE;
        }
        long pending;
        int inFlight;
        synchronized (this) {
            if (jobId != null && retrying.contains(jobId)) {
                throw ApiException.conflict(ErrorCode.JOB_NOT_FAILED, jobId,
                        "Only FAILED jobs can be retried; another retry of this job is being accepted");
            }
            if (key != null && creating.containsKey(key)) {
                log.info("Queue admission: a create with an Idempotency-Key another request is being accepted with"
                        + " shares its turn (reserved={} cap={})", reserved + 1, maxPending);
                return reserve(jobId, key);
            }
            pending = counts.queuedAfter(clock.instant().minusMillis(staleAfterMs));
            inFlight = reserved;
            if (pending + inFlight < maxPending) {
                return reserve(jobId, key);
            }
        }
        RuntimeException instead = insteadOfQueueFull == null ? null : insteadOfQueueFull.get();
        if (instead != null) {
            throw instead;
        }
        onRefused.accept(op);
        log.info("Queue full: refused op={} jobId={} pending={} reserved={} cap={} retryAfter={}s",
                op.tag(), jobId == null ? "-" : jobId, pending, inFlight, maxPending, retryAfterSeconds);
        throw ApiException.queueFull(jobId, retryAfterSeconds);
    }

    /** Gauge beside.jobs.pending.stale (docs/METRICS.md §5.1): 0 while the cap is off, NaN while the count fails. */
    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("beside.jobs.pending.stale", this, gate -> gate.staleRows)
                .description("PENDING jobs older than admission.stale-after-ms, which the queue cap does not count;"
                        + " counted once a minute while the cap is on")
                .strongReference(true)
                .register(registry);
    }

    /**
     * Once a minute while the cap is on, also with no traffic: counts the PENDING rows the cap leaves out for the gauge
     * and warns when there are any. A failed count reads NaN until one succeeds, warns once per streak, and is only
     * DEBUG while the context closes (the database closes before the scheduler stops).
     */
    @Scheduled(fixedDelay = STALE_CHECK_MS, initialDelay = STALE_CHECK_MS)
    public void checkStaleRows() {
        if (maxPending == 0) {
            return;
        }
        Instant cutoff = clock.instant().minusMillis(staleAfterMs);
        long stale;
        try {
            stale = counts.queuedUpTo(cutoff);
        } catch (RuntimeException e) {
            if (closing || Double.isNaN(staleRows)) {
                log.debug("Queue admission: stale PENDING rows not counted: {}", e.toString());
            } else {
                log.warn("Queue admission: stale PENDING rows not counted, beside_jobs_pending_stale reads NaN until a"
                        + " count succeeds: {}", e.toString());
            }
            staleRows = Double.NaN;
            return;
        }
        staleRows = stale;
        if (stale > 0) {
            log.warn("Queue admission: {} PENDING job(s) queued before {} have waited longer than"
                    + " admission.stale-after-ms={} and are not counted toward the cap: a job no worker runs, or a"
                    + " wait longer than the setting allows (InterruptedJobRecovery ends PENDING jobs at the next restart)",
                    stale, cutoff, staleAfterMs);
        }
    }

    @EventListener(ContextClosedEvent.class)
    public void contextClosing() {
        closing = true;
    }

    /** Holds the monitor. */
    private Permit reserve(String jobId, String key) {
        reserved++;
        if (jobId != null) {
            retrying.add(jobId);
        }
        if (key != null) {
            creating.merge(key, 1, Integer::sum);
        }
        return new Permit(this, jobId, key);
    }

    private synchronized void release(String jobId, String key) {
        reserved--;
        if (jobId != null) {
            retrying.remove(jobId);
        }
        if (key != null) {
            creating.computeIfPresent(key, (k, holders) -> holders > 1 ? holders - 1 : null);
        }
    }

    /** Places held by requests being admitted (tests). */
    synchronized int reserved() {
        return reserved;
    }

    int maxPending() {
        return maxPending;
    }

    int retryAfterSeconds() {
        return retryAfterSeconds;
    }

    long staleAfterMs() {
        return staleAfterMs;
    }

    private static PendingCounts pendingJobs(JobRepository jobs) {
        return new PendingCounts() {
            @Override
            public long queuedAfter(Instant cutoff) {
                return jobs.countByStatusAndQueuedAtAfter(JobStatus.PENDING, cutoff);
            }

            @Override
            public long queuedUpTo(Instant cutoff) {
                return jobs.countByStatusAndQueuedAtLessThanEqual(JobStatus.PENDING, cutoff);
            }
        };
    }

    /**
     * One admitted request's place in the queue, held until its write is committed. Closing it more than once gives
     * the place back once. The permit of a gate that is off holds nothing.
     */
    public static final class Permit implements AutoCloseable {
        static final Permit NONE = new Permit(null, null, null);

        private final AdmissionGate gate;
        private final String jobId;
        private final String key;
        private final AtomicBoolean open = new AtomicBoolean(true);

        private Permit(AdmissionGate gate, String jobId, String key) {
            this.gate = gate;
            this.jobId = jobId;
            this.key = key;
        }

        @Override
        public void close() {
            if (gate != null && open.compareAndSet(true, false)) {
                gate.release(jobId, key);
            }
        }
    }
}
