package com.example.mockbackend.service;

import com.example.mockbackend.domain.JobStatus;
import com.example.mockbackend.exception.ApiException;
import com.example.mockbackend.exception.ErrorCode;
import com.example.mockbackend.repository.JobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Queue cap (REVIEW_CHECKLIST #21, a proposal before the contract meeting; off by default): v1 create and retry are
 * refused with 503 QUEUE_FULL and Retry-After while the PENDING jobs plus the requests being admitted reach
 * admission.max-pending. v0 is frozen and never asks.
 * <ul>
 *   <li>A request takes a {@link Permit} before its INSERT (create) or its FAILED → PENDING update (retry) and closes
 *       it once that write is committed. admit and close use this object's monitor, so requests checked at the same
 *       time cannot all pass the same count and the cap is exact. Between the commit and the close a request is counted
 *       twice (row and reservation), which only makes the cap stricter for that moment; it is never under-counted.</li>
 *   <li>The reservation lives inside one request (try-with-resources), so no worker path has to give it back. One JVM
 *       owns the database file (no AUTO_SERVER), so this count plus the database is the whole queue.</li>
 *   <li>PENDING rows queued longer ago than admission.stale-after-ms (default inference.offline-wait-ms + 10 min) are
 *       not counted: a row whose worker was lost would otherwise hold a place until the next restart. They are reported
 *       with one WARN line at most once a minute.</li>
 *   <li>Two retries of the same job: while one holds a permit the other gets 409 JOB_NOT_FAILED (it would lose the
 *       conditional update anyway), so a full queue never answers it 503 for a job another retry is re-queuing.</li>
 *   <li>max-pending 0 (the default) admits everything without a query: behaviour stays as without the cap.</li>
 * </ul>
 * Every refusal answered 503 is counted (beside.jobs.refused{op}) and logged once here with the numbers it was
 * decided on, so a load test can check pending + reserved ≥ cap for each refusal.
 */
@Component
public class AdmissionGate {
    /** How often the stale PENDING rows are counted for the WARN line (only while the cap is on). */
    static final Duration STALE_CHECK_INTERVAL = Duration.ofMinutes(1);
    /** Added to inference.offline-wait-ms when admission.stale-after-ms is not set. */
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
    private final Set<String> retrying = new HashSet<>();
    private Instant nextStaleCheck = Instant.MIN;

    @Autowired
    public AdmissionGate(JobRepository jobs, JobMetrics metrics,
                         @Value("${admission.max-pending:0}") int maxPending,
                         @Value("${admission.retry-after-seconds:165}") int retryAfterSeconds,
                         @Value("${admission.stale-after-ms:-1}") long staleAfterMs,
                         @Value("${inference.offline-wait-ms:0}") long offlineWaitMs) {
        this(pendingJobs(jobs), metrics::jobRefused, Clock.systemUTC(), maxPending, retryAfterSeconds,
                staleAfterMs < 0 ? Math.max(0L, offlineWaitMs) + STALE_SLACK_MS : staleAfterMs);
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
                    + " on (leave it unset for inference.offline-wait-ms + 10 min), got " + staleAfterMs);
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

    /** A place for a v1 create, or 503 QUEUE_FULL (jobId null). Close the permit once the job row is committed. */
    public Permit admit(Op op) {
        return admit(op, null, null);
    }

    /**
     * A place for the retry of jobId, or: 409 JOB_NOT_FAILED while another retry of the same job holds a permit;
     * otherwise, when the queue is full, whatever {@code insteadOfQueueFull} returns (read outside the lock, for a job
     * another retry already re-queued), or 503 QUEUE_FULL naming the job when it returns null.
     */
    public Permit admitRetry(String jobId, Supplier<? extends RuntimeException> insteadOfQueueFull) {
        return admit(Op.RETRY, jobId, insteadOfQueueFull);
    }

    private Permit admit(Op op, String jobId, Supplier<? extends RuntimeException> insteadOfQueueFull) {
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
            Instant now = clock.instant();
            Instant cutoff = now.minusMillis(staleAfterMs);
            reportStaleRows(now, cutoff);
            pending = counts.queuedAfter(cutoff);
            inFlight = reserved;
            if (pending + inFlight < maxPending) {
                reserved++;
                if (jobId != null) {
                    retrying.add(jobId);
                }
                return new Permit(this, jobId);
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

    /** Holds the monitor. Counts the stale rows at most once per STALE_CHECK_INTERVAL and warns when there are any. */
    private void reportStaleRows(Instant now, Instant cutoff) {
        if (now.isBefore(nextStaleCheck)) {
            return;
        }
        nextStaleCheck = now.plus(STALE_CHECK_INTERVAL);
        long stale = counts.queuedUpTo(cutoff);
        if (stale > 0) {
            log.warn("Queue admission: {} PENDING job(s) queued before {} are older than admission.stale-after-ms={} and"
                    + " not counted toward the cap; a worker may have lost them (they end at the next restart)",
                    stale, cutoff, staleAfterMs);
        }
    }

    private synchronized void release(String jobId) {
        reserved--;
        if (jobId != null) {
            retrying.remove(jobId);
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
        static final Permit NONE = new Permit(null, null);

        private final AdmissionGate gate;
        private final String jobId;
        private final AtomicBoolean open = new AtomicBoolean(true);

        private Permit(AdmissionGate gate, String jobId) {
            this.gate = gate;
            this.jobId = jobId;
        }

        @Override
        public void close() {
            if (gate != null && open.compareAndSet(true, false)) {
                gate.release(jobId);
            }
        }
    }
}
