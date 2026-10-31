package com.example.mockbackend.service;

import com.example.mockbackend.domain.Job;
import com.example.mockbackend.domain.JobStatus;
import com.example.mockbackend.exception.ErrorCode;
import com.example.mockbackend.repository.JobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The state changes that race with each other, made under one lock: a worker starting a job (PENDING → PROCESSING),
 * the client canceling it (POST /cancel: PENDING → FAILED CANCELED), a worker giving up on it (offline wait over,
 * shutdown), a retry (FAILED → PENDING, the next attempt) and a deletion (only while COMPLETED or FAILED). Each re-reads the job inside the lock and acts only when it is still in the expected state and attempt,
 * so a canceled job never starts, a started job is never canceled, and a worker left over from an earlier attempt
 * (cancel, then retry) leaves the new attempt alone. Terminal saves still go through JobFinisher.
 * One server per database file (CLAUDE.md), so a lock inside this process is enough. Each section is one read and
 * one save (plus JobFinisher's row and line), so holding it briefly costs nothing next to minutes of generation.
 */
@Component
@RequiredArgsConstructor
public class JobTransitions {
    private static final Set<JobStatus> PENDING = EnumSet.of(JobStatus.PENDING);
    private static final Set<JobStatus> UNFINISHED = EnumSet.of(JobStatus.PENDING, JobStatus.PROCESSING);
    private static final Set<JobStatus> FAILED = EnumSet.of(JobStatus.FAILED);

    private final JobRepository jobRepository;
    private final JobFinisher jobFinisher;

    /** Whether the job is still PENDING in that attempt. For wait loops; {@link #start} makes the real decision. */
    public boolean isPending(String jobId, int attempt) {
        return jobRepository.findById(jobId).filter(job -> matches(job, PENDING, attempt)).isPresent();
    }

    /**
     * PENDING → PROCESSING with {@code progress} (null = unknown), only while the job is still PENDING in that attempt.
     * Empty when it was canceled, retried into a newer attempt, started by another dispatch, or is gone.
     */
    public synchronized Optional<Job> start(String jobId, int attempt, Double progress) {
        Job job = jobRepository.findById(jobId).orElse(null);
        if (job == null || !matches(job, PENDING, attempt)) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        job.setProcessingStartedAt(now);
        job.setProgress(progress);
        job.setUpdatedAt(now);
        job.setStatus(JobStatus.PROCESSING);
        jobRepository.save(job);
        return Optional.of(job);
    }

    /**
     * PENDING → FAILED with {@code code} through JobFinisher, only while the job is still PENDING (in that attempt;
     * null = whatever attempt it is in). Empty when it is not.
     */
    public synchronized Optional<Job> failPending(String jobId, Integer attempt, ErrorCode code, String message,
                                                  String workerType) {
        return failIf(jobId, attempt, PENDING, code, message, workerType);
    }

    /** FAILED with {@code code} if that attempt has not ended (PENDING or PROCESSING): a worker stopped by a shutdown. */
    public synchronized Optional<Job> failUnfinished(String jobId, int attempt, ErrorCode code, String message,
                                                     String workerType) {
        return failIf(jobId, attempt, UNFINISHED, code, message, workerType);
    }

    private Optional<Job> failIf(String jobId, Integer attempt, Set<JobStatus> from, ErrorCode code, String message,
                                 String workerType) {
        Job job = jobRepository.findById(jobId).orElse(null);
        if (job == null || !matches(job, from, attempt)) {
            return Optional.empty();
        }
        jobFinisher.fail(job, code, message, workerType);
        return Optional.of(job);
    }

    /**
     * FAILED → PENDING as the next attempt (result, error and timings cleared, queued at {@code queuedAt}), only while
     * the job is still FAILED in the attempt the caller read. Empty when another request retried it first (it is
     * PENDING or further now) or it is gone.
     */
    public synchronized Optional<Job> retry(String jobId, int attempt, Instant queuedAt) {
        Job job = jobRepository.findById(jobId).orElse(null);
        if (job == null || !matches(job, FAILED, attempt)) {
            return Optional.empty();
        }
        job.setErrorCode(null);
        job.setErrorMessage(null);
        job.setResultPath(null);
        job.setHairResultPath(null);
        job.setProcessingStartedAt(null);
        job.setFinishedAt(null);
        job.setProgress(0.0);
        job.setAttempt(attempt + 1);
        job.setQueuedAt(queuedAt);
        job.setUpdatedAt(queuedAt);
        job.setStatus(JobStatus.PENDING);
        jobRepository.save(job);
        return Optional.of(job);
    }

    /**
     * Back to the FAILED attempt {@code before} (as read before the retry), if the job is still PENDING in the attempt
     * the retry made: a dispatch that shutdown refused leaves no PENDING job that no worker will run.
     */
    public synchronized void undoRetry(Job before, int retriedAttempt) {
        Job job = jobRepository.findById(before.getId()).orElse(null);
        if (job != null && matches(job, PENDING, retriedAttempt)) {
            jobRepository.save(before);
        }
    }

    /**
     * Runs {@code remove} with the jobs, under the lock, when every one of them is COMPLETED or FAILED (re-read here):
     * a retry cannot make one PENDING between the check and the deletion. Returns the first job that is still
     * PENDING or PROCESSING (nothing removed then); empty when the jobs were removed. Jobs already gone are skipped.
     */
    public synchronized Optional<Job> removeIfFinished(List<String> jobIds, Consumer<List<Job>> remove) {
        List<Job> jobs = new ArrayList<>();
        for (String jobId : jobIds) {
            Job job = jobRepository.findById(jobId).orElse(null);
            if (job == null) {
                continue;
            }
            if (UNFINISHED.contains(job.getStatus())) {
                return Optional.of(job);
            }
            jobs.add(job);
        }
        remove.accept(jobs);
        return Optional.empty();
    }

    private static boolean matches(Job job, Set<JobStatus> statuses, Integer attempt) {
        return statuses.contains(job.getStatus()) && (attempt == null || job.getAttempt() == attempt);
    }
}
