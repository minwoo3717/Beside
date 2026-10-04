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

import java.util.List;

/**
 * Jobs survive a restart in the H2 database, their @Async worker threads do not. At startup every job still
 * PENDING or PROCESSING is closed as FAILED (INTERNAL_ERROR), so clients stop polling and can POST /retry.
 * <p>
 * Runs in afterSingletonsInstantiated, i.e. before the web server accepts requests, so a job created by a new
 * request can never be caught. Assumes one server per database file (the H2 URL has no AUTO_SERVER on purpose).
 * Each closed run goes through JobFinisher, so it also gets its events.jsonl line (result FAILED:INTERNAL_ERROR).
 */
@Component
@RequiredArgsConstructor
public class InterruptedJobRecovery implements SmartInitializingSingleton {
    private final JobRepository jobRepository;
    private final JobFinisher jobFinisher;
    private final JobWorker jobWorker;
    private final Logger log = LoggerFactory.getLogger(InterruptedJobRecovery.class);

    @Override
    public void afterSingletonsInstantiated() {
        List<Job> interrupted = jobRepository.findByStatusIn(List.of(JobStatus.PENDING, JobStatus.PROCESSING));
        for (Job job : interrupted) {
            JobStatus was = job.getStatus();
            jobFinisher.fail(job, ErrorCode.INTERNAL_ERROR, "Interrupted by a server restart while " + was
                    + "; retry with POST /api/v1/jobs/" + job.getId() + "/retry", jobWorker.type());
            log.warn("jobId={} was {} when the server stopped -> FAILED (INTERNAL_ERROR)", job.getId(), was);
        }
    }
}
