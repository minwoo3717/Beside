package com.example.mockbackend.service;

import com.example.mockbackend.domain.JobRun;
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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Server measurement log, docs/METRICS.md §1: one JSON line per run that ends COMPLETED or FAILED, written from the
 * same JobRun as the job_runs row so file and table never disagree. Field names are shared with the other tracks;
 * attempt and finishedAt are server-only additions, and the inference numbers (§2) are null unless /infer answered.
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

    public synchronized void recordFinished(JobRun run) {
        if (path == null) {
            return;
        }
        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("jobId", run.getJobId());
            event.put("attempt", run.getAttempt());
            event.put("finishedAt", run.getFinishedAt().toString());
            event.put("uploadBytes", run.getUploadBytes());
            event.put("uploadMs", run.getUploadMs());
            event.put("queuedMs", run.getQueuedMs());
            event.put("processingMs", run.getProcessingMs());
            event.put("totalMs", run.getTotalMs());
            event.put("workerType", run.getWorkerType());
            event.put("result", run.result());
            // METRICS §2 numbers and the run facts come after the original ten fields (additive, 2026-10-06).
            event.put("inferMs", run.getInferMs());
            event.put("gpuPeakMB", run.getGpuPeakMB());
            event.put("modelParams", run.getModelParams());
            event.put("outputVertices", run.getOutputVertices());
            event.put("outputTriangles", run.getOutputTriangles());
            event.put("convertMs", run.getConvertMs());
            event.put("glbBytes", run.getGlbBytes());
            event.put("passthrough", run.getPassthrough());
            event.put("modelVersion", run.getModelVersion());
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, JSON.writeValueAsString(event) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            log.warn("Could not append the event of jobId={} to {}: {}", run.getJobId(), path, e.toString());
        }
    }
}
