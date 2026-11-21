package com.example.mockbackend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
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
 * A public share link of one completed job, table "share_links". The token is the only secret: 128 random bits as
 * base64url (22 characters), so the job id never has to appear on the public share page. A link is active until
 * {@code expiresAt} (exclusive) unless it was revoked. Rows are kept after expiry or revocation (no cleanup job).
 * Persistable so that issuing always INSERTs: a token collision fails loudly instead of overwriting another link.
 * New columns must be nullable (boxed) types: ddl-auto=update cannot add a NOT NULL column to a table that has rows.
 */
@Entity
@Table(name = "share_links", indexes = @Index(name = "idx_share_links_job_id", columnList = "jobId"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ShareLink implements Persistable<String> {
    @Id
    @Column(length = 64)
    private String token;

    @Column(nullable = false, length = 36)
    private String jobId;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant expiresAt;

    /** Null while the link was never revoked. */
    private Instant revokedAt;

    @Transient
    @Getter(AccessLevel.NONE)
    private boolean persisted;

    public static ShareLink issue(String token, String jobId, Instant createdAt, Instant expiresAt) {
        ShareLink link = new ShareLink();
        link.token = token;
        link.jobId = jobId;
        link.createdAt = createdAt;
        link.expiresAt = expiresAt;
        return link;
    }

    /** Not revoked and {@code now} is before the expiry. */
    public boolean isActiveAt(Instant now) {
        return revokedAt == null && now.isBefore(expiresAt);
    }

    public void revoke(Instant at) {
        revokedAt = at;
    }

    @Override
    public String getId() {
        return token;
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
