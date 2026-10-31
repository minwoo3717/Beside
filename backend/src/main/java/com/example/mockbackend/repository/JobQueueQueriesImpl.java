package com.example.mockbackend.repository;

import com.example.mockbackend.domain.JobStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** JobQueueQueries of JobRepository (Spring Data finds it by the Impl suffix). */
class JobQueueQueriesImpl implements JobQueueQueries {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * An earlier queuedAt, or the same instant and a smaller id: written as a range on queuedAt so H2 can count from
     * idx_jobs_status_queued_at_id instead of scanning every job on each poll.
     */
    @Override
    @Transactional(readOnly = true)
    public QueueSnapshot queueSnapshot(Instant queuedAt, String id) {
        long before = entityManager.createQuery("select count(j) from Job j where j.status = :status"
                        + " and j.queuedAt <= :queuedAt and (j.queuedAt < :queuedAt or j.id < :id)", Long.class)
                .setParameter("status", JobStatus.PENDING)
                .setParameter("queuedAt", queuedAt)
                .setParameter("id", id)
                .getSingleResult();
        List<Instant> running = entityManager
                .createQuery("select j.processingStartedAt from Job j where j.status = :status", Instant.class)
                .setParameter("status", JobStatus.PROCESSING)
                .getResultList();
        return new QueueSnapshot(before, running);
    }
}
