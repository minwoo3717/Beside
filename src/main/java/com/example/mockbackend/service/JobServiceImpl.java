package com.example.mockbackend.service;

import com.example.mockbackend.domain.Job;
import com.example.mockbackend.domain.JobStatus;
import com.example.mockbackend.repository.JobRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Service implementation that handles job lifecycle and starts a mock worker asynchronously.
 */
@Service
@RequiredArgsConstructor
public class JobServiceImpl implements JobService {
    private final JobRepository jobRepository;
    private final Logger log = LoggerFactory.getLogger(JobServiceImpl.class);

    @Value("${storage.upload.dir:storage/uploads}")
    private String uploadDir;

    @Value("${storage.result.sample:storage/results/sample-dog.glb}")
    private String sampleResultPath;

    @PostConstruct
    public void init() throws IOException {
        Files.createDirectories(Path.of(uploadDir));
        Files.createDirectories(Path.of("storage/results"));
    }

    @Override
    public Job createJob(Job job) {
        return jobRepository.save(job);
    }

    @Override
    public Job getJob(String id) {
        return jobRepository.findById(id).orElse(null);
    }

    /**
     * Store uploaded files and start mock worker asynchronously.
     */
    public Job submitJob(List<MultipartFile> photos) throws IOException {
        if (photos == null || photos.isEmpty()) {
            throw new IllegalArgumentException("No files uploaded");
        }

        Job job = new Job();
        String jobId = UUID.randomUUID().toString();
        job.setId(jobId);
        job.setStatus(JobStatus.PENDING);
        job.setCreatedAt(Instant.now());

        List<String> savedPaths = new ArrayList<>();
        for (MultipartFile file : photos) {
            String original = file.getOriginalFilename();
            String filename = jobId + "_" + (original != null ? original : "upload");
            Path target = Path.of(uploadDir).resolve(filename);
            Files.copy(file.getInputStream(), target);
            savedPaths.add(target.toString());
        }
        job.setUploadedFiles(savedPaths);
        job.setResultPath(null);
        jobRepository.save(job);

        // start mock worker
        runMockWorkerAsync(jobId);
        log.info("Job created: {} status={}", jobId, job.getStatus());
        return job;
    }

    @Async
    public void runMockWorkerAsync(String jobId) {
        Instant start = Instant.now();
        try {
            Job job = jobRepository.findById(jobId).orElseThrow(() -> new IllegalStateException("Job not found: " + jobId));
            // after ~2s, set PROCESSING
            Thread.sleep(2000);
            job.setStatus(JobStatus.PROCESSING);
            job.setUpdatedAt(Instant.now());
            jobRepository.save(job);
            log.info("jobId={} stage=PROCESSING", jobId);

            // after ~5s, set COMPLETED and point to sample result
            Thread.sleep(5000);
            job.setStatus(JobStatus.COMPLETED);
            job.setResultPath(sampleResultPath);
            job.setUpdatedAt(Instant.now());
            job.setDurationMs(Duration.between(start, Instant.now()).toMillis());
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
