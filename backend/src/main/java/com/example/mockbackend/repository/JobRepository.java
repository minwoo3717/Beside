package com.example.mockbackend.repository;

import com.example.mockbackend.domain.Job;
import com.example.mockbackend.domain.JobStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * Jobs in the H2 file database (Spring Data JPA). save() merges, so workers can save the copy they loaded earlier.
 */
public interface JobRepository extends JpaRepository<Job, String> {

    /** The newest jobs (createdAt desc, then id desc for a stable order); the page size is the limit. */
    @Query("select j from Job j order by j.createdAt desc, j.id desc")
    List<Job> findNewestFirst(Pageable page);

    /**
     * The jobs that come after the given one in the same order (keyset paging): a page costs the page size, not the
     * whole table. The {@code <=} on createdAt lets H2 start from the cursor in idx_jobs_created_at_id.
     */
    @Query("select j from Job j where j.createdAt <= :createdAt and (j.createdAt < :createdAt or j.id < :id)"
            + " order by j.createdAt desc, j.id desc")
    List<Job> findNewestFirstAfter(@Param("createdAt") Instant createdAt, @Param("id") String id, Pageable page);

    /** Jobs in any of the given states (startup recovery of jobs interrupted by a restart). */
    List<Job> findByStatusIn(Collection<JobStatus> statuses);

    /** How many jobs are in the given state (JobQueueGauge, every few seconds — not per request). */
    long countByStatus(JobStatus status);

    /**
     * Retry: moves the job from FAILED attempt {@code attempt} to PENDING attempt {@code attempt + 1} in one
     * conditional UPDATE and clears what the failed run left (the same fields JobServiceImpl.retry sets). True only
     * for the one request that moved it: every other retry of the same attempt changes no row, so only one of several
     * simultaneous retries dispatches a run.
     */
    default boolean startRetry(String id, int attempt, Instant now) {
        return updateForRetry(id, attempt, now, JobStatus.FAILED, JobStatus.PENDING) == 1;
    }

    /** startRetry's UPDATE (the statuses are parameters so JobStatusColumnConverter writes them). */
    @Modifying
    @Transactional
    @Query("update Job j set j.status = :to, j.attempt = j.attempt + 1, j.errorCode = null, j.errorMessage = null,"
            + " j.resultPath = null, j.hairResultPath = null, j.processingStartedAt = null, j.finishedAt = null,"
            + " j.progress = 0.0, j.queuedAt = :now, j.updatedAt = :now"
            + " where j.id = :id and j.status = :from and j.attempt = :attempt")
    int updateForRetry(@Param("id") String id, @Param("attempt") int attempt, @Param("now") Instant now,
                       @Param("from") JobStatus from, @Param("to") JobStatus to);
}
