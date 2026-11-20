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

    /**
     * How many jobs are in the given state (JobQueueGauge, every few seconds). Per request only the queue cap counts,
     * with {@link #countByStatusAndQueuedAtAfter}.
     */
    long countByStatus(JobStatus status);

    /**
     * Jobs in the given state queued after the cutoff: AdmissionGate's count, once per v1 create and retry while the
     * queue cap is on (REVIEW_CHECKLIST #21, proposal before the contract meeting). One range of
     * idx_jobs_status_queued_at_id, so its cost does not grow with the finished jobs the table keeps (no retention, #23).
     */
    long countByStatusAndQueuedAtAfter(JobStatus status, Instant cutoff);

    /**
     * Jobs in the given state queued at or before the cutoff: AdmissionGate's stale-row check (gauge
     * beside.jobs.pending.stale and a WARN line), once a minute while the queue cap is on, never per request.
     */
    long countByStatusAndQueuedAtLessThanEqual(JobStatus status, Instant cutoff);

    /**
     * Retry as one conditional update: a {@code failed} job becomes {@code pending} with the next attempt and without
     * what the failed attempt left. Returns 1 when it changed the row and 0 when the job is not (or no longer) FAILED,
     * so of two retries racing on one FAILED job only one wins and the attempt is dispatched once. The states are
     * parameters (JobStatus.FAILED, JobStatus.PENDING) so they are written through JobStatusColumnConverter.
     */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("update Job j set j.status = :pending, j.attempt = j.attempt + 1, j.errorCode = null, j.errorMessage = null,"
            + " j.resultPath = null, j.hairResultPath = null, j.processingStartedAt = null, j.finishedAt = null,"
            + " j.progress = 0.0, j.queuedAt = :now, j.updatedAt = :now where j.id = :id and j.status = :failed")
    int startRetry(@Param("id") String id, @Param("now") Instant now,
                   @Param("failed") JobStatus failed, @Param("pending") JobStatus pending);
}
