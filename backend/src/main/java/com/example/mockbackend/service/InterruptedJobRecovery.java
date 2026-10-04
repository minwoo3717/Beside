package com.example.mockbackend.service;

import com.example.mockbackend.domain.Job;
import com.example.mockbackend.domain.JobStatus;
import com.example.mockbackend.exception.ErrorCode;
import com.example.mockbackend.repository.JobRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Jobs survive a restart in the H2 database, their @Async worker threads do not. At startup every job still
 * PENDING or PROCESSING is closed as FAILED (INTERNAL_ERROR), so clients stop polling and can POST /retry.
 * <p>
 * Runs in afterSingletonsInstantiated, i.e. before the web server accepts requests, so a job created by a new
 * request can never be caught. Assumes one server per database file (the H2 URL has no AUTO_SERVER on purpose).
 */
@Component
@RequiredArgsConstructor
public class InterruptedJobRecovery implements SmartInitializingSingleton {
    private final JobRepository jobRepository;
    private final Logger log = LoggerFactory.getLogger(InterruptedJobRecovery.class);

    @Override
    public void afterSingletonsInstantiated() {
        List<Job> interrupted = jobRepository.findByStatusIn(List.of(JobStatus.PENDING, JobStatus.PROCESSING));
        Instant now = Instant.now();
        for (Job job : interrupted) {
            JobStatus was = job.getStatus();
            job.setErrorCode(ErrorCode.INTERNAL_ERROR.name());
            job.setErrorMessage("Interrupted by a server restart while " + was
                    + "; retry with POST /api/v1/jobs/" + job.getId() + "/retry");
            job.setProgress(null);
            job.setFinishedAt(now);
            job.setUpdatedAt(now);
            job.setStatus(JobStatus.FAILED);
            jobRepository.save(job);
            log.warn("jobId={} was {} when the server stopped -> FAILED (INTERNAL_ERROR)", job.getId(), was);
        }
    }
}
