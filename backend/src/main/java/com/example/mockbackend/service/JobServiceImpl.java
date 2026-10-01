package com.example.mockbackend.service;

import com.example.mockbackend.domain.Job;
import com.example.mockbackend.domain.JobStatus;
import com.example.mockbackend.repository.JobRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private final MockJobWorker mockJobWorker;
    private final Logger log = LoggerFactory.getLogger(JobServiceImpl.class);

    @Value("${storage.upload.dir:storage/uploads}")
    private String uploadDir;

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

        // Invoke another Spring bean so @Async runs through its proxy.
        mockJobWorker.runMockWorkerAsync(jobId);
        log.info("Job created: {} status={}", jobId, job.getStatus());
        return job;
    }

}
