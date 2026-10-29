package com.example.mockbackend.repository;

import com.example.mockbackend.domain.JobRun;
import com.example.mockbackend.domain.JobStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * Per-run measurements (docs/METRICS.md §1.1). Production inserts only through JobFinisher; reads are the admin export
 * and dashboard (AdminService), the queue estimate (QueueEstimator), tests and inspection.
 */
public interface JobRunRepository extends JpaRepository<JobRun, String> {

    List<JobRun> findByJobIdOrderByAttemptAsc(String jobId);

    /** Rows with {@code from <= finishedAt < to}, oldest first (the measurement table order of METRICS §1.1). */
    @Query("select r from JobRun r where r.finishedAt >= :from and r.finishedAt < :to order by r.finishedAt asc, r.jobId asc, r.attempt asc")
    List<JobRun> findFinishedBetween(@Param("from") Instant from, @Param("to") Instant to);

    /** Every run of the given jobs (the dashboard's recent-jobs table takes the last one per job). */
    List<JobRun> findByJobIdIn(Collection<String> jobIds);

    /**
     * processingMs of the latest runs of one worker type in {@code status}, newest first, leaving out passthrough runs
     * (no model ran) and runs without a processing time. QueueEstimator asks for COMPLETED, 20 at a time.
     */
    @Query("select r.processingMs from JobRun r where r.workerType = :workerType and r.status = :status"
            + " and r.processingMs is not null and (r.passthrough is null or r.passthrough = false)"
            + " order by r.finishedAt desc")
    List<Long> findRecentProcessingMs(@Param("workerType") String workerType, @Param("status") JobStatus status,
                                      Pageable page);
}
