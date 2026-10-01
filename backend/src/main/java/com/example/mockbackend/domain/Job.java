package com.example.mockbackend.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * Job domain representing a 3D generation job.
 */
@Data
@NoArgsConstructor
public class Job {
    private String id;
    // Publish worker state changes (and preceding result metadata) to HTTP threads.
    private volatile JobStatus status;
    private Instant createdAt;
    private Instant updatedAt;
    private List<String> uploadedFiles; // paths of uploaded photos
    private String resultPath; // path to resulting GLB when completed
    private long durationMs;
}
