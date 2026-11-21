package com.example.mockbackend.repository;

import java.time.Instant;
import java.util.List;

/**
 * The two reads behind JobResponse.queue of a PENDING job (QueueEstimator, on every poll), as one read-only
 * transaction: one session and one connection instead of one of each per statement, and through the EntityManager,
 * whose query plans Hibernate caches by their text, instead of @Query methods, which Spring Data JPA 3.2 parses again
 * (ANTLR) on every call. Each statement still reads the latest commits (H2's READ COMMITTED), as the two separate calls
 * did: it is one transaction, not one snapshot.
 */
public interface JobQueueQueries {

    /** The PENDING jobs queued before (queuedAt, then id) and the processingStartedAt of every PROCESSING job. */
    default QueueSnapshot queueSnapshot(Instant queuedAt, String id) {
        return queueSnapshot(queuedAt, id, null);
    }

    /**
     * Same, counting only the PENDING jobs queued after {@code countedAfter} (null = all): the rows the queue cap
     * counts (AdmissionGate leaves out those queued at or before now minus admission.stale-after-ms).
     */
    QueueSnapshot queueSnapshot(Instant queuedAt, String id, Instant countedAfter);

    record QueueSnapshot(long pendingBefore, List<Instant> processingStartedAt) {
    }
}
