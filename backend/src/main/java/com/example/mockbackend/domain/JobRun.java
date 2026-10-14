package com.example.mockbackend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

import java.time.Instant;

/**
 * One finished run (attempt) of a job, table "job_runs": the server timings of docs/METRICS.md §1 and the inference
 * numbers of §2 in one row, so the stage-5 measurement table is a SQL query (METRICS §1.1). Written once by
 * JobFinisher right after the terminal save of the job and never updated. Persistable so that saving always INSERTs:
 * finishing the same attempt twice raises DataIntegrityViolationException instead of overwriting the first
 * measurement. Holds no server paths, so an export can be shared as it is.
 * New columns must be nullable (boxed) types: ddl-auto=update cannot add a NOT NULL column to a table that has rows.
 */
@Entity
@Table(name = "job_runs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class JobRun implements Persistable<String> {
    /** {jobId}:{attempt}. */
    @Id
    @Column(length = 48)
    private String id;

    @Column(nullable = false, length = 36)
    private String jobId;

    @Column(nullable = false)
    private Integer attempt;

    /** "mock" | "real" (JobWorker.type()). */
    @Column(nullable = false, length = 16)
    private String workerType;

    /** COMPLETED or FAILED. Plain VARCHAR without a CHECK constraint (see JobStatusColumnConverter). */
    @Convert(converter = JobStatusColumnConverter.class)
    @Column(nullable = false, length = 16)
    private JobStatus status;

    /** ErrorCode name when FAILED, else null. */
    @Column(length = 64)
    private String errorCode;

    @Column(nullable = false)
    private Instant finishedAt;

    // docs/METRICS.md §1 — server timings, the same numbers as JobResponse.timings (JobTimings)
    private Long uploadBytes;
    private Long uploadMs;
    private Long queuedMs;
    private Long processingMs;
    private Long totalMs;

    // docs/METRICS.md §2 — copied from the /infer answer; null without one (mock worker, FAILED runs)
    private Long inferMs;
    /** Named explicitly: the default strategy would make it gpu_peakmb. */
    @Column(name = "gpu_peak_mb")
    private Long gpuPeakMB;
    private Long modelParams;
    private Long outputVertices;
    private Long outputTriangles;
    private Long convertMs;
    /** Size of the stored base GLB (= JobResponse.asset.bytes). */
    private Long glbBytes;
    /** True when the inference service answered with its sample instead of a model result: leave out of measurements. */
    private Boolean passthrough;
    /** Optional /infer answer field, to tell the baseline from improved models. */
    @Column(length = 128)
    private String modelVersion;

    @Transient
    @Getter(AccessLevel.NONE)
    private boolean persisted;

    /** The row of the run that {@code job} just finished; {@code finishedAt} is the finisher's clock. */
    public static JobRun of(Job job, Instant finishedAt, String workerType, RunDetails details) {
        RunDetails d = details != null ? details : RunDetails.NONE;
        JobTimings timings = JobTimings.of(job, finishedAt);
        JobRun run = new JobRun();
        run.id = job.getId() + ":" + job.getAttempt();
        run.jobId = job.getId();
        run.attempt = job.getAttempt();
        run.workerType = workerType;
        run.status = job.getStatus();
        run.errorCode = job.getStatus() == JobStatus.FAILED ? job.getErrorCode() : null;
        run.finishedAt = finishedAt;
        run.uploadBytes = job.getUploads().stream().mapToLong(StoredUpload::bytes).sum();
        run.uploadMs = job.getUploadMs();
        run.queuedMs = timings.queuedMs();
        run.processingMs = timings.processingMs();
        run.totalMs = timings.totalMs();
        run.inferMs = d.inferMs();
        run.gpuPeakMB = d.gpuPeakMB();
        run.modelParams = d.modelParams();
        run.outputVertices = d.outputVertices();
        run.outputTriangles = d.outputTriangles();
        run.convertMs = d.convertMs();
        run.glbBytes = d.glbBytes();
        run.passthrough = d.passthrough();
        run.modelVersion = d.modelVersion();
        return run;
    }

    /** The events.jsonl result column: COMPLETED, or FAILED:&lt;error.code&gt;. */
    public String result() {
        return status == JobStatus.FAILED ? "FAILED:" + errorCode : String.valueOf(status);
    }

    @Override
    public boolean isNew() {
        return !persisted;
    }

    @PostLoad
    @PostPersist
    void markPersisted() {
        persisted = true;
    }
}
