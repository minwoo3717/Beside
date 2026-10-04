package com.example.mockbackend.service;

import com.example.mockbackend.domain.Job;
import com.example.mockbackend.domain.JobStatus;
import com.example.mockbackend.domain.JobTimings;
import com.example.mockbackend.domain.StoredUpload;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Server measurement log, docs/METRICS.md §1: one JSON line per run that ends COMPLETED or FAILED.
 * Field names are shared with the other tracks; attempt and finishedAt are server-only additions.
 * Writing never throws: a full disk or a bad path must not fail the job itself.
 */
@Component
public class EventLog {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path path;
    private final Logger log = LoggerFactory.getLogger(EventLog.class);

    public EventLog(@Value("${storage.events.path:storage/events.jsonl}") String path) {
        this.path = path == null || path.isBlank() ? null : Path.of(path);
    }

    public synchronized void recordFinished(Job job, String workerType) {
        if (path == null) {
            return;
        }
        try {
            Instant finishedAt = job.getFinishedAt() != null ? job.getFinishedAt() : Instant.now();
            JobTimings timings = JobTimings.of(job, finishedAt);
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("jobId", job.getId());
            event.put("attempt", job.getAttempt());
            event.put("finishedAt", finishedAt.toString());
            event.put("uploadBytes", job.getUploads().stream().mapToLong(StoredUpload::bytes).sum());
            event.put("uploadMs", job.getUploadMs());
            event.put("queuedMs", timings.queuedMs());
            event.put("processingMs", timings.processingMs());
            event.put("totalMs", timings.totalMs());
            event.put("workerType", workerType);
            event.put("result", job.getStatus() == JobStatus.FAILED ? "FAILED:" + job.getErrorCode() : String.valueOf(job.getStatus()));
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, JSON.writeValueAsString(event) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            log.warn("Could not append the event of jobId={} to {}: {}", job.getId(), path, e.toString());
        }
    }
}
