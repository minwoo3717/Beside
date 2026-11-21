package com.example.mockbackend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Objects;

/**
 * One line of the Unity app's metrics.csv (docs/METRICS.md §3), imported through POST /api/v1/admin/client-metrics
 * (§3.1), table "client_metrics". Values are the app's own measurements; the server only stores them. The jobId may
 * belong to another server (no foreign key). A line is a duplicate of a stored row when jobId, device and e2eMs match.
 * New columns must be nullable (boxed) types: ddl-auto=update cannot add a NOT NULL column to a table that has rows.
 */
@Entity
@Table(name = "client_metrics", indexes = @Index(name = "idx_client_metrics_job_id", columnList = "jobId"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ClientMetric {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String jobId;

    private Long uploadMs;
    private Long waitMs;
    private Long downloadMs;
    private Long loadMs;
    /** Named explicitly so the column reads e2e_ms whatever the naming strategy does with the digit. */
    @Column(name = "e2e_ms")
    private Long e2eMs;
    private Double avgFps;
    private Double minFps;
    /** Named explicitly: the default strategy would make it memmb (like gpu_peakmb in JobRun). */
    @Column(name = "mem_mb")
    private Long memMB;
    /** SystemInfo.deviceModel; null when the cell was empty. */
    @Column(length = 256)
    private String device;

    @Column(nullable = false)
    private Instant importedAt;

    public static ClientMetric of(String jobId, long uploadMs, long waitMs, long downloadMs, long loadMs, long e2eMs,
                                  double avgFps, double minFps, long memMB, String device, Instant importedAt) {
        ClientMetric metric = new ClientMetric();
        metric.jobId = jobId;
        metric.uploadMs = uploadMs;
        metric.waitMs = waitMs;
        metric.downloadMs = downloadMs;
        metric.loadMs = loadMs;
        metric.e2eMs = e2eMs;
        metric.avgFps = avgFps;
        metric.minFps = minFps;
        metric.memMB = memMB;
        metric.device = device;
        metric.importedAt = importedAt;
        return metric;
    }

    /** The duplicate check of the import: the same job measured on the same phone with the same end-to-end time. */
    public DedupKey dedupKey() {
        return new DedupKey(jobId, device, e2eMs);
    }

    public record DedupKey(String jobId, String device, Long e2eMs) {
        public DedupKey {
            Objects.requireNonNull(jobId, "jobId");
        }
    }
}
