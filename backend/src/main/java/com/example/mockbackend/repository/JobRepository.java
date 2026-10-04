package com.example.mockbackend.repository;

import com.example.mockbackend.domain.Job;
import com.example.mockbackend.domain.JobStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

/**
 * Jobs in the H2 file database (Spring Data JPA). save() merges, so workers can save the copy they loaded earlier.
 */
public interface JobRepository extends JpaRepository<Job, String> {

    /** All jobs, newest first (createdAt desc, then id desc for a stable order). */
    @Query("select j from Job j order by j.createdAt desc, j.id desc")
    List<Job> findAllNewestFirst();

    /** Jobs in any of the given states (startup recovery of jobs interrupted by a restart). */
    List<Job> findByStatusIn(Collection<JobStatus> statuses);
}
