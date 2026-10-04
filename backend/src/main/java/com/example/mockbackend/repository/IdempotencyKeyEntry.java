package com.example.mockbackend.repository;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.Instant;

/**
 * Idempotency-Key → jobId (POST /api/v1/jobs). Kept in the database so a replay after a restart still returns the
 * same job. Persistable so that saving a new entry always INSERTs: a second request racing on the same key gets a
 * DataIntegrityViolationException instead of silently overwriting the first job id (merge would update the row).
 */
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyKeyEntry implements Persistable<String> {
    @Id
    @Column(length = 128)
    private String idempotencyKey;

    @Column(nullable = false, length = 36)
    private String jobId;

    @Column(nullable = false)
    private Instant createdAt;

    @Transient
    private boolean persisted;

    protected IdempotencyKeyEntry() {
    }

    public IdempotencyKeyEntry(String idempotencyKey, String jobId, Instant createdAt) {
        this.idempotencyKey = idempotencyKey;
        this.jobId = jobId;
        this.createdAt = createdAt;
    }

    @Override
    public String getId() {
        return idempotencyKey;
    }

    public String getJobId() {
        return jobId;
    }

    public Instant getCreatedAt() {
        return createdAt;
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
