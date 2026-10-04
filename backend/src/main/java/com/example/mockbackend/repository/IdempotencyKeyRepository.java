package com.example.mockbackend.repository;

import org.springframework.data.jpa.repository.JpaRepository;

/** Idempotency-Key entries (table idempotency_keys). Use saveAndFlush for new keys so a duplicate fails right away. */
public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKeyEntry, String> {
}
