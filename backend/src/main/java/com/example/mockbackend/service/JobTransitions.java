package com.example.mockbackend.service;

import com.example.mockbackend.domain.Job;
import com.example.mockbackend.domain.JobStatus;
import com.example.mockbackend.exception.ErrorCode;
import com.example.mockbackend.repository.JobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * The state changes that race with each other, made under one lock: a worker starting a job (PENDING → PROCESSING),
 * the client canceling it (POST /cancel: PENDING → FAILED CANCELED) and a worker giving up on it (offline wait over,
 * shutdown). Each re-reads the job inside the lock and acts only when it is still in the expected state and attempt,
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

    private static boolean matches(Job job, Set<JobStatus> statuses, Integer attempt) {
        return statuses.contains(job.getStatus()) && (attempt == null || job.getAttempt() == attempt);
    }
}
