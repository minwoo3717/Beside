package com.example.mockbackend.repository;

import com.example.mockbackend.domain.Job;
import com.example.mockbackend.domain.JobStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}
