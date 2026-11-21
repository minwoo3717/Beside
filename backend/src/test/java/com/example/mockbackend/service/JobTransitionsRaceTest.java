package com.example.mockbackend.service;

import com.example.mockbackend.domain.Job;
import com.example.mockbackend.domain.JobStatus;
import com.example.mockbackend.repository.JobRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a request that read the job a moment ago may still do once it holds JobTransitions' lock: the decision is
 * made on a fresh read, so a stale view (a second retry of the same attempt, a delete racing a retry) changes nothing.
 */
@SpringBootTest(properties = "mock.worker.pending-ms=60000")
class JobTransitionsRaceTest {

    @Autowired
    JobTransitions transitions;

    @Autowired
    JobRepository jobRepository;

    @Test
    void aSecondRetryOfTheSameAttemptDoesNotTouchTheRunTheFirstStarted() {
        Job failed = jobRepository.save(job(JobStatus.FAILED, 1));
        Instant now = Instant.now();
        assertThat(transitions.retry(failed.getId(), 1, now)).isPresent();          // PENDING, attempt 2
        assertThat(transitions.start(failed.getId(), 2, null)).isPresent();         // the worker started it
        assertThat(transitions.retry(failed.getId(), 1, now.plusMillis(1))).isEmpty(); // the other request, stale
        Job after = jobRepository.findById(failed.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(JobStatus.PROCESSING);
        assertThat(after.getAttempt()).isEqualTo(2);
    }

    @Test
    void aDeletedJobIsNotRetriedBackIntoTheTable() {
        Job failed = jobRepository.save(job(JobStatus.FAILED, 1));
        jobRepository.deleteById(failed.getId());
        assertThat(transitions.retry(failed.getId(), 1, Instant.now())).isEmpty();
        assertThat(jobRepository.findById(failed.getId())).isEmpty();
    }

    @Test
    void aDeleteChecksAgainUnderTheLock() {
        Job retried = jobRepository.save(job(JobStatus.FAILED, 1));
        Job done = jobRepository.save(job(JobStatus.COMPLETED, 1));
        assertThat(transitions.retry(retried.getId(), 1, Instant.now())).isPresent(); // PENDING after the caller's check
        List<Job> removed = new ArrayList<>();
        assertThat(transitions.removeIfFinished(List.of(done.getId(), retried.getId()), removed::addAll))
                .get().extracting(Job::getId).isEqualTo(retried.getId());
        assertThat(removed).isEmpty(); // all or nothing: the finished one stays too
        assertThat(transitions.removeIfFinished(List.of(done.getId(), "gone-" + UUID.randomUUID()), removed::addAll))
                .isEmpty();
        assertThat(removed).extracting(Job::getId).containsExactly(done.getId());
    }

    @Test
    void undoRetryPutsBackOnlyTheAttemptItMade() {
        Job failed = jobRepository.save(job(JobStatus.FAILED, 1));
        Job before = jobRepository.findById(failed.getId()).orElseThrow();
        Job retried = transitions.retry(failed.getId(), 1, Instant.now()).orElseThrow();
        transitions.undoRetry(before, retried.getAttempt());
        Job after = jobRepository.findById(failed.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(after.getAttempt()).isEqualTo(1);
        assertThat(after.getErrorCode()).isEqualTo("INFERENCE_FAILED");
    }

    private static Job job(JobStatus status, int attempt) {
        Instant then = Instant.now().minusSeconds(60);
        Job job = new Job();
        job.setId(UUID.randomUUID().toString());
        job.setStatus(status);
        job.setCreatedAt(then);
        job.setUpdatedAt(then);
        job.setQueuedAt(then);
        job.setFinishedAt(then);
        job.setAttempt(attempt);
        job.setErrorCode(status == JobStatus.FAILED ? "INFERENCE_FAILED" : null);
        job.setErrorMessage(status == JobStatus.FAILED ? "race test" : null);
        job.setUploads(new ArrayList<>());
        return job;
    }
}
