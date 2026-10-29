package com.example.mockbackend;

import com.example.mockbackend.domain.Job;
import com.example.mockbackend.domain.JobRun;
import com.example.mockbackend.domain.JobStatus;
import com.example.mockbackend.domain.RunDetails;
import com.example.mockbackend.repository.JobRepository;
import com.example.mockbackend.repository.JobRunRepository;
import com.example.mockbackend.service.JobFinishedEvent;
import com.example.mockbackend.service.QueueEstimator;
import com.example.mockbackend.service.QueueEstimator.Estimate;
import com.example.mockbackend.service.QueueEstimator.Outlook;
import com.example.mockbackend.service.QueueEstimator.Position;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QueueEstimator on the JPA slice, so the job_runs filters run as the real query: the median processingMs of the
 * latest 20 COMPLETED, non-passthrough runs of this worker type, the place in the queue (earlier queuedAt, then id),
 * the remaining time of a PENDING and of a PROCESSING job, the reason, and the 60 s cache dropped by a completed run.
 */
@DataJpaTest
class QueueEstimatorTest {
    private static final Instant NOW = Instant.parse("2026-10-30T12:00:00Z");

    @Autowired
    JobRepository jobs;

    @Autowired
    JobRunRepository runs;

    private final MutableClock clock = new MutableClock(NOW);
    private boolean inferenceReady = true;
    private QueueEstimator estimator;

    @BeforeEach
    void create() {
        estimator = new QueueEstimator(jobs, runs, "real", () -> inferenceReady, clock);
    }

    @Test
    void noCompletedRunMeansNoEstimate() {
        failedRun("failed", 5_000, 1);
        completedRun("passthrough", 4_000, 2, true, "real");
        completedRun("other-worker", 3_000, 3, false, "mock");
        Job pending = pending("p", NOW.minusSeconds(10));

        Outlook outlook = estimator.outlook(pending);
        assertThat(outlook.queue()).isEqualTo(new Position(0, null));
        assertThat(outlook.estimate()).as("failed, passthrough and other-worker runs do not count").isNull();
        assertThat(estimator.outlook(processing("r", NOW.minusMillis(100))).estimate()).isNull();
    }

    @Test
    void medianOfTheRecentCompletedRunsOfThisWorkerOnly() {
        completedRun("a", 1_000, 1, false, "real");
        completedRun("b", 3_000, 2, false, "real");
        completedRun("c", 2_000, 3, null, "real"); // passthrough unknown counts as a model run
        completedRun("pt", 90_000, 4, true, "real");
        completedRun("mock", 70_000, 5, false, "mock");
        failedRun("f", 80_000, 6);
        Job running = processing("r", NOW.minusMillis(500));

        Outlook outlook = estimator.outlook(running);
        assertThat(outlook.queue()).as("queue only while PENDING").isNull();
        assertThat(outlook.estimate()).isEqualTo(new Estimate(1_500L, 3)); // median 2000 - 500 ms already spent
    }

    @Test
    void anEvenNumberOfRunsTakesTheMeanOfTheMiddleTwo() {
        completedRun("a", 4_000, 1, false, "real");
        completedRun("b", 1_000, 2, false, "real");
        completedRun("c", 3_000, 3, false, "real");
        completedRun("d", 2_000, 4, false, "real");

        assertThat(estimator.outlook(pending("p", NOW)).estimate()).isEqualTo(new Estimate(2_500L, 4));
    }

    @Test
    void onlyTheLatestTwentyRunsCount() {
        for (int i = 0; i < 5; i++) {
            completedRun("old-" + i, 600_000, i, false, "real"); // finished first
        }
        for (int i = 0; i < 20; i++) {
            completedRun("new-" + i, 2_000, 100 + i, false, "real");
        }

        assertThat(estimator.outlook(pending("p", NOW)).estimate()).isEqualTo(new Estimate(2_000L, 20));
    }

    @Test
    void aPendingJobWaitsForTheJobsAheadAndTheRestOfTheRunningOne() {
        completedRun("a", 1_000, 1, false, "real");
        completedRun("b", 2_000, 2, false, "real");
        completedRun("c", 3_000, 3, false, "real");
        Job running = processing("running", NOW.minusMillis(500));
        Job first = pending("job-1", NOW.minusSeconds(30));
        Job sameInstantSmallerId = pending("job-2", NOW.minusSeconds(20));
        Job sameInstantLargerId = pending("job-3", NOW.minusSeconds(20));
        Job last = pending("job-4", NOW.minusSeconds(10));

        // median 2000: own run + one per job ahead, minus the 500 ms the running job has already spent
        assertThat(estimator.outlook(first)).isEqualTo(new Outlook(new Position(1, null), new Estimate(3_500L, 3)));
        assertThat(estimator.outlook(sameInstantSmallerId).queue().ahead()).isEqualTo(2);
        assertThat(estimator.outlook(sameInstantLargerId).queue().ahead()).as("same queuedAt: id decides").isEqualTo(3);
        assertThat(estimator.outlook(last)).isEqualTo(new Outlook(new Position(4, null), new Estimate(9_500L, 3)));

        // A running job that overruns the median counts only up to the median; it never makes a negative wait.
        clock.advance(Duration.ofSeconds(10));
        assertThat(estimator.outlook(first).estimate()).isEqualTo(new Estimate(2_000L, 3));
        assertThat(estimator.outlook(running).estimate()).as("PROCESSING past the median: 0").isEqualTo(new Estimate(0L, 3));
    }

    @Test
    void theReasonIsInferenceOfflineWhileTheServiceIsNotUp() {
        Job pending = pending("p", NOW);
        inferenceReady = false;
        assertThat(estimator.outlook(pending).queue()).isEqualTo(new Position(0, QueueEstimator.INFERENCE_OFFLINE));
        inferenceReady = true;
        assertThat(estimator.outlook(pending).queue()).isEqualTo(new Position(0, null));
    }

    @Test
    void finishedJobsHaveNoOutlook() {
        completedRun("a", 1_000, 1, false, "real");
        Job completed = job("done", JobStatus.COMPLETED, NOW.minusSeconds(5));
        Job failed = job("failed", JobStatus.FAILED, NOW.minusSeconds(5));

        assertThat(estimator.outlook(completed)).isEqualTo(new Outlook(null, null));
        assertThat(estimator.outlook(failed)).isEqualTo(new Outlook(null, null));
    }

    @Test
    void theMedianIsCachedForAMinuteAndDroppedWhenARunCompletes() {
        completedRun("a", 1_000, 1, false, "real");
        Job pending = pending("p", NOW);
        assertThat(estimator.outlook(pending).estimate()).isEqualTo(new Estimate(1_000L, 1));

        completedRun("b", 3_000, 2, false, "real");
        assertThat(estimator.outlook(pending).estimate()).as("cached").isEqualTo(new Estimate(1_000L, 1));
        clock.advance(Duration.ofSeconds(61));
        assertThat(estimator.outlook(pending).estimate()).as("read again after 60 s").isEqualTo(new Estimate(2_000L, 2));

        completedRun("c", 5_000, 3, false, "real");
        assertThat(estimator.outlook(pending).estimate()).as("cached").isEqualTo(new Estimate(2_000L, 2));
        estimator.onJobFinished(new JobFinishedEvent("c", 1, JobStatus.FAILED, "CANCELED", 10L, "real", null, false));
        assertThat(estimator.outlook(pending).estimate()).as("a failed run changes nothing").isEqualTo(new Estimate(2_000L, 2));
        estimator.onJobFinished(new JobFinishedEvent("c", 1, JobStatus.COMPLETED, null, 5_000L, "real", null, false));
        assertThat(estimator.outlook(pending).estimate()).as("a completed run drops the cache").isEqualTo(new Estimate(3_000L, 3));
    }

    // ------------------------------------------------------------------ helpers

    private Job pending(String id, Instant queuedAt) {
        return jobs.save(job(id, JobStatus.PENDING, queuedAt));
    }

    private Job processing(String id, Instant startedAt) {
        Job job = job(id, JobStatus.PROCESSING, startedAt.minusSeconds(1));
        job.setProcessingStartedAt(startedAt);
        return jobs.save(job);
    }

    private static Job job(String id, JobStatus status, Instant queuedAt) {
        Job job = new Job();
        job.setId(id);
        job.setStatus(status);
        job.setCreatedAt(queuedAt);
        job.setUpdatedAt(queuedAt);
        job.setQueuedAt(queuedAt);
        job.setAttempt(1);
        job.setUploads(new ArrayList<>());
        return job;
    }

    /** A completed run that took {@code processingMs}; a larger {@code order} finished later. */
    private void completedRun(String jobId, long processingMs, int order, Boolean passthrough, String workerType) {
        run(jobId, JobStatus.COMPLETED, null, processingMs, order, workerType,
                new RunDetails(null, null, null, null, null, null, 1L, passthrough, null));
    }

    private void failedRun(String jobId, long processingMs, int order) {
        run(jobId, JobStatus.FAILED, "INFERENCE_FAILED", processingMs, order, "real", RunDetails.NONE);
    }

    private void run(String jobId, JobStatus status, String errorCode, long processingMs, int order, String workerType,
                     RunDetails details) {
        Instant finishedAt = NOW.minus(Duration.ofHours(1)).plusSeconds(order);
        Job job = job(jobId, status, finishedAt.minusMillis(processingMs + 100));
        job.setProcessingStartedAt(finishedAt.minusMillis(processingMs));
        job.setFinishedAt(finishedAt);
        job.setErrorCode(errorCode);
        runs.saveAndFlush(JobRun.of(job, finishedAt, workerType, details));
    }

    /** A clock the test moves by hand. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
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
            return now;
        }
    }
}
