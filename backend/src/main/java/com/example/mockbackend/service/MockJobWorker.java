package com.example.mockbackend.service;

import com.example.mockbackend.domain.Job;
import com.example.mockbackend.domain.JobStatus;
import com.example.mockbackend.repository.JobRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * Separate bean so calls from JobServiceImpl pass through Spring's async proxy.
 */
@Service
@RequiredArgsConstructor
public class MockJobWorker {
    private final JobRepository jobRepository;
    private final Logger log = LoggerFactory.getLogger(MockJobWorker.class);

    @Value("${storage.result.sample:storage/results/sample-dog.glb}")
    private String sampleResultPath;

    @Async
    public void runMockWorkerAsync(String jobId) {
        Instant start = Instant.now();
        try {
            Job job = jobRepository.findById(jobId)
                    .orElseThrow(() -> new IllegalStateException("Job not found: " + jobId));
            Thread.sleep(2000);
            job.setUpdatedAt(Instant.now());
            job.setStatus(JobStatus.PROCESSING);
            jobRepository.save(job);
            log.info("jobId={} stage=PROCESSING", jobId);

            Thread.sleep(3000);
            job.setResultPath(sampleResultPath);
            job.setUpdatedAt(Instant.now());
            job.setDurationMs(Duration.between(start, Instant.now()).toMillis());
            // Publish completion only after the download path and metadata are ready.
            job.setStatus(JobStatus.COMPLETED);
            jobRepository.save(job);
            log.info("jobId={} stage=COMPLETED durationMs={}", jobId, job.getDurationMs());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Job job = jobRepository.findById(jobId).orElse(null);
            if (job != null) {
                job.setStatus(JobStatus.FAILED);
                jobRepository.save(job);
            }
            log.error("Mock worker interrupted for jobId={}", jobId, e);
        }
    }
}
