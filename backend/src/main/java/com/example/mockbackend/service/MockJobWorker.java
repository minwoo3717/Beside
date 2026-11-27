package com.example.mockbackend.service;

import com.example.mockbackend.domain.Job;
import com.example.mockbackend.domain.RunDetails;
import com.example.mockbackend.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Locale;

/**
 * Profile "mock" (default): no GPU. PENDING for pending-ms, PROCESSING for processing-ms, then COMPLETED
 * with the sample GLB, or FAILED (INFERENCE_FAILED) when an uploaded filename contains the fail token.
 * After the PENDING time it starts the job only if it is still PENDING in the dispatched attempt (JobTransitions),
 * so a job canceled (or canceled and retried) meanwhile is not touched.
 * Separate bean so calls from JobServiceImpl pass through Spring's async proxy. Runs end through JobFinisher; an
 * interrupted worker (shutdown) or an unexpected error ends the run with INTERNAL_ERROR. Both waits end at once when
 * the server stops ({@link ShutdownSignal}): Spring's executor waits for running tasks during the stop.
 */
@Service
@Profile("mock")
public class MockJobWorker implements JobWorker {
    private final JobTransitions jobTransitions;
    private final JobFinisher jobFinisher;
    /** Each run is a trace of its own, as in the real worker: beside.job.run with the two waits as stages. */
    private final JobTracing tracing;
    private final ShutdownSignal shutdown;
    private final Logger log = LoggerFactory.getLogger(MockJobWorker.class);

    /** Without tracing (tests that build the worker themselves). */
    public MockJobWorker(JobTransitions jobTransitions, JobFinisher jobFinisher) {
        this(jobTransitions, jobFinisher, JobTracing.NOOP, new ShutdownSignal());
    }

    @Autowired
    public MockJobWorker(JobTransitions jobTransitions, JobFinisher jobFinisher, JobTracing tracing,
                         ShutdownSignal shutdown) {
        this.jobTransitions = jobTransitions;
        this.jobFinisher = jobFinisher;
        this.tracing = tracing;
        this.shutdown = shutdown;
    }

    @Value("${storage.result.sample:storage/results/sample-dog.glb}")
    private String sampleResultPath;

    @Value("${mock.worker.pending-ms:2000}")
    private long pendingMs;

    @Value("${mock.worker.processing-ms:3000}")
    private long processingMs;

    @Value("${mock.worker.fail-when-filename-contains:fail}")
    private String failToken;

    @Override
    public String type() {
        return "mock";
    }

    @Async
    @Override
    public void dispatch(String jobId, int attempt) {
        Instant start = Instant.now();
        JobTracing.Run trace = tracing.startRun(jobId, attempt, type());
        // Every line of this run carries the job (MDC does not follow @Async; the pool thread must not keep it).
        MDC.put(RealJobWorker.MDC_JOB_ID, jobId);
        MDC.put(RealJobWorker.MDC_ATTEMPT, String.valueOf(attempt));
        try {
            try (JobTracing.Scoped pending = tracing.stage("beside.job.mock-pending")) {
                shutdown.sleep(pendingMs);
            }
            Job job = jobTransitions.start(jobId, attempt, 0.5).orElse(null);
            if (job == null) {
                trace.notStarted();
                log.info("jobId={} attempt={} not started: no longer PENDING (canceled or retried)", jobId, attempt);
                return;
            }
            log.info("jobId={} stage=PROCESSING traceId={}", jobId, trace.traceId());

            try (JobTracing.Scoped processing = tracing.stage("beside.job.mock-processing")) {
                shutdown.sleep(processingMs);
            }
            if (shouldFail(job)) {
                jobFinisher.fail(job, ErrorCode.INFERENCE_FAILED,
                        "Mock failure: an uploaded filename contains '" + failToken + "'", type());
                trace.failed(ErrorCode.INFERENCE_FAILED.name(), null);
                log.info("jobId={} stage=FAILED (mock trigger)", jobId);
                return;
            }
            jobFinisher.complete(job, sampleResultPath, start, type(), RunDetails.NONE);
            trace.completed();
            log.info("jobId={} stage=COMPLETED durationMs={}", jobId, job.getDurationMs());
        } catch (InterruptedException e) {
            trace.failed(ErrorCode.INTERNAL_ERROR.name(), e);
            // Record the failure before restoring the interrupt flag: JDBC calls can fail while it is set.
            try {
                markInterrupted(jobId, attempt);
            } catch (RuntimeException databaseClosing) {
                log.warn("jobId={} interrupted during shutdown; InterruptedJobRecovery fails it at the next startup", jobId);
            } finally {
                ShutdownSignal.restoreInterrupt(e);   // not for the stop signal: the thread was never interrupted
            }
        } catch (RuntimeException unexpected) {
            // Escaping the @Async method would leave the job PENDING or PROCESSING until the next restart.
            trace.failed(ErrorCode.INTERNAL_ERROR.name(), unexpected);
            failUnended(jobId, attempt, unexpected);
        } finally {
            MDC.remove(RealJobWorker.MDC_JOB_ID);
            MDC.remove(RealJobWorker.MDC_ATTEMPT);
            trace.close();
        }
    }

    /**
     * Ends the run INTERNAL_ERROR, reading the job again under JobTransitions' lock (the copy the run held may be ahead
     * of the database when its save failed). A run that already ended, a canceled job and a newer attempt stay as they
     * are. One attempt: when the failure cannot be written either (database down) this only logs, and
     * InterruptedJobRecovery fails the job at the next startup.
     */
    private void failUnended(String jobId, int attempt, RuntimeException unexpected) {
        log.error("jobId={} mock worker error", jobId, unexpected);
        try {
            if (jobTransitions.failUnfinished(jobId, attempt, ErrorCode.INTERNAL_ERROR,
                    "Mock worker error (" + unexpected.getClass().getSimpleName() + ")", type()).isEmpty()) {
                log.warn("jobId={} attempt={} already ended, gone or retried; left as it is", jobId, attempt);
                return;
            }
            log.warn("jobId={} stage=FAILED code=INTERNAL_ERROR ({})", jobId, unexpected.getClass().getSimpleName());
        } catch (RuntimeException notRecorded) {
            log.error("jobId={} could not record the failure ({}); InterruptedJobRecovery fails it at the next startup",
                    jobId, notRecorded.toString());
        }
    }

    /** Only this attempt, and only if it has not ended: a canceled job keeps its CANCELED. */
    private void markInterrupted(String jobId, int attempt) {
        if (jobTransitions.failUnfinished(jobId, attempt, ErrorCode.INTERNAL_ERROR,
                "Mock worker interrupted (server shutting down)", type()).isPresent()) {
            log.warn("jobId={} stage=FAILED (mock worker interrupted)", jobId);
        }
    }

    private boolean shouldFail(Job job) {
        if (failToken == null || failToken.isBlank()) {
            return false;
        }
        String token = failToken.toLowerCase(Locale.ROOT);
        return job.getUploads().stream()
                .anyMatch(upload -> upload.name() != null && upload.name().toLowerCase(Locale.ROOT).contains(token));
    }
}
