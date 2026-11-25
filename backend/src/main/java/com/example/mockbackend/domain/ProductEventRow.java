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

/**
 * One product event (docs/METRICS.md §6, REVIEW_CHECKLIST #24), table "product_events". Insert-only: written by
 * ProductEventService after the per-event checks, read by the admin export and the session funnel, deleted only by the
 * 30-day retention (by hand until #23). Holds no IP, file name, server address, share token or free text: the props are
 * the checked enums and numbers of ProductEventCatalog, as JSON. Not part of a job: JobDeletionService leaves it (a
 * measurement, like job_runs). New columns must be nullable (boxed) types: ddl-auto=update cannot add a NOT NULL column
 * to a table that has rows.
 */
@Entity
@Table(name = "product_events", indexes = {
        @Index(name = "idx_product_events_received_at", columnList = "receivedAt"),
        @Index(name = "idx_product_events_session_seq", columnList = "sessionId, seq")})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductEventRow {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Server clock when the batch arrived; the admin range filters and the retention use this. */
    @Column(nullable = false)
    private Instant receivedAt;

    /** Random UUID the app (or the share viewer's localStorage) made once; not a device id. */
    @Column(nullable = false, length = 36)
    private String installId;

    @Column(nullable = false, length = 36)
    private String sessionId;

    @Column(nullable = false, length = 32)
    private String appVersion;

    /** android | web */
    @Column(nullable = false, length = 16)
    private String platform;

    /** One of ProductEventCatalog.NAMES. */
    @Column(nullable = false, length = 32)
    private String name;

    /** The client's clock when it happened. Named event_at: AT is a keyword in some SQL dialects. */
    @Column(name = "event_at", nullable = false)
    private Instant at;

    /** 0, 1, 2 ... within the session; (sessionId, seq) is stored once. */
    @Column(nullable = false)
    private Integer seq;

    @Column(length = 36)
    private String jobId;

    /** One of ProductEventCatalog.SCREENS, or null. */
    @Column(length = 16)
    private String screen;

    /** The checked props as a JSON object, null when none survived the checks. */
    @Column(length = 512)
    private String propsJson;

    /** management.metrics.tags.env of the server that stored it: local | prod | loadtest. */
    @Column(nullable = false, length = 16)
    private String env;

    public static ProductEventRow of(Instant receivedAt, String installId, String sessionId, String appVersion, String platform,
                                     String name, Instant at, int seq, String jobId, String screen, String propsJson,
                                     String env) {
        ProductEventRow row = new ProductEventRow();
        row.receivedAt = receivedAt;
        row.installId = installId;
        row.sessionId = sessionId;
        row.appVersion = appVersion;
        row.platform = platform;
        row.name = name;
        row.at = at;
        row.seq = seq;
        row.jobId = jobId;
        row.screen = screen;
        row.propsJson = propsJson;
        row.env = env;
        return row;
    }
}
