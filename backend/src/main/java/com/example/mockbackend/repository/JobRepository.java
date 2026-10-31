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

    /** Jobs in any of the given states (startup recovery of jobs interrupted by a restart, the admin queue card). */
    List<Job> findByStatusIn(Collection<JobStatus> statuses);

    /** Number of jobs in one state (admin overview). */
    long countByStatus(JobStatus status);

    /**
     * Jobs in {@code status} that were queued before the given point: an earlier queuedAt, or the same instant and a
     * smaller id (QueueEstimator: the PENDING jobs ahead of one). Written as a range on queuedAt so H2 can count
     * from idx_jobs_status_queued_at_id instead of scanning every job on each poll.
     */
    @Query("select count(j) from Job j where j.status = :status"
            + " and j.queuedAt <= :queuedAt and (j.queuedAt < :queuedAt or j.id < :id)")
    long countQueuedBefore(@Param("status") JobStatus status, @Param("queuedAt") Instant queuedAt, @Param("id") String id);

    /** When each job in {@code status} started processing (QueueEstimator: how far the running jobs are). */
    @Query("select j.processingStartedAt from Job j where j.status = :status")
    List<Instant> findProcessingStartedAt(@Param("status") JobStatus status);

    // ------------------------------------------------------------------ library (tag pets)

    /** The jobs of one pet, newest first (createdAt desc, then id desc), the order of the job list. */
    @Query("select j from Job j where j.petId = :petId order by j.createdAt desc, j.id desc")
    List<Job> findByPetNewestFirst(@Param("petId") String petId);

    /** The jobs of several pets, newest first (listPets groups them). */
    @Query("select j from Job j where j.petId in :petIds order by j.createdAt desc, j.id desc")
    List<Job> findByPetsNewestFirst(@Param("petIds") Collection<String> petIds);

    /**
     * Moves one job into a pet (null = unassigned). Job.petId is not updatable through saves, so this statement is the
     * only way to change it; a worker saving its older copy of the job later keeps the new value.
     */
    @Modifying
    @Transactional
    @Query("update Job j set j.petId = :petId where j.id = :id")
    int updatePetId(@Param("id") String id, @Param("petId") String petId);

    /** Every job of the pet back to unassigned (deleting the pet without its jobs). */
    @Modifying
    @Transactional
    @Query("update Job j set j.petId = null where j.petId = :petId")
    int unassignPet(@Param("petId") String petId);
}
