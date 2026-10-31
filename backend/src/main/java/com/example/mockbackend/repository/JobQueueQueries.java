package com.example.mockbackend.repository;

import java.time.Instant;
import java.util.List;

/**
 * The two reads behind JobResponse.queue of a PENDING job (QueueEstimator, on every poll), as one read-only
 * transaction: one session and one connection instead of one of each per statement, and through the EntityManager,
 * whose query plans Hibernate caches by their text, instead of @Query methods, which Spring Data JPA 3.2 parses again
 * (ANTLR) on every call. Both values come from the same snapshot.
 */
public interface JobQueueQueries {

    /** The PENDING jobs queued before (queuedAt, then id) and the processingStartedAt of every PROCESSING job. */
    QueueSnapshot queueSnapshot(Instant queuedAt, String id);

    record QueueSnapshot(long pendingBefore, List<Instant> processingStartedAt) {
    }
}
