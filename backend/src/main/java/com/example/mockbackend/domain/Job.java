package com.example.mockbackend.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Job domain representing a 3D generation job.
 * A worker thread fills the fields and HTTP threads read them: fill result metadata first and
 * change {@code status} (volatile) last so readers never see COMPLETED without its asset.
 * This class is never serialized directly; see LegacyJobResponse (v0) and JobResponseMapper (v1).
 */
@Data
@NoArgsConstructor
public class Job {
    private String id;
    private volatile JobStatus status;
    private Instant createdAt;
    private Instant updatedAt;
    /** Accepted (or last retried) at. Reference point for v1 timings. */
    private Instant queuedAt;
    private Instant processingStartedAt;
    private Instant finishedAt;
    /** 0~1, null when unknown. */
    private Double progress;
    /** 1 for the first run, +1 per retry. */
    private int attempt;
    private List<StoredUpload> uploads = new ArrayList<>();
    /** Server path of the completed base GLB. Never exposed in v1. */
    private String resultPath;
    /** Server path of the completed hair GLB, if any. Never exposed in v1. */
    private String hairResultPath;
    /** ErrorCode name when FAILED. */
    private String errorCode;
    private String errorMessage;
    /** v0 compatibility: worker start to completion in ms. */
    private long durationMs;
}
