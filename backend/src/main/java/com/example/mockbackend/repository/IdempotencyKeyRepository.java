package com.example.mockbackend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** Idempotency-Key entries (table idempotency_keys). Use saveAndFlush for new keys so a duplicate fails right away. */
public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKeyEntry, String> {

    /** Releases every key that answered with this job (the job is being deleted: the key may make a new job). */
    @Modifying
    @Transactional
    @Query("delete from IdempotencyKeyEntry e where e.jobId = :jobId")
    int deleteByJobId(@Param("jobId") String jobId);
}
