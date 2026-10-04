package com.example.mockbackend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A 3D generation job, stored in the H2 file database (table "jobs").
 * Workers and HTTP threads each load their own copy and every repository call is its own short transaction:
 * fill result metadata and switch {@code status} in the same save, so readers never see COMPLETED without its asset.
 * Never serialized directly; see LegacyJobResponse (v0) and JobResponseMapper (v1).
 * New fields must be nullable (boxed) types: ddl-auto=update cannot add a NOT NULL column to a table that has rows.
 */
@Entity
@Table(name = "jobs")
@Getter
@Setter
@NoArgsConstructor
public class Job {
    @Id
    @Column(length = 36)
    private String id;

    /** Plain VARCHAR without a CHECK constraint (see JobStatusColumnConverter): adding a JobStatus value needs no migration. */
    @Convert(converter = JobStatusColumnConverter.class)
    @Column(nullable = false, length = 16)
    private JobStatus status;

    @Column(nullable = false)
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
    /** Uploaded photos as one JSON column: [{name, bytes, path}]. */
    @Convert(converter = StoredUploadListConverter.class)
    @Column(length = 16384)
    private List<StoredUpload> uploads = new ArrayList<>();
    /** Server path of the completed base GLB. Never exposed in v1. */
    @Column(length = 1024)
    private String resultPath;
    /** Server path of the completed hair GLB, if any. Never exposed in v1. */
    @Column(length = 1024)
    private String hairResultPath;
    /** ErrorCode name when FAILED. */
    @Column(length = 64)
    private String errorCode;
    @Column(length = 4000)
    private String errorMessage;
    /** v0 compatibility: worker start to completion in ms. */
    private long durationMs;
}
