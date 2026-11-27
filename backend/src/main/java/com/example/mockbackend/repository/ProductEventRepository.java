package com.example.mockbackend.repository;

import com.example.mockbackend.domain.ProductEventRow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/** Product events (docs/METRICS.md §6). Written only by ProductEventService.ingest; insert-only. */
public interface ProductEventRepository extends JpaRepository<ProductEventRow, Long> {

    /** The seqs of this session that are already stored, among {@code seqs} (the duplicate check of a batch sent again). */
    @Query("select e.seq from ProductEventRow e where e.sessionId = :sessionId and e.seq in :seqs")
    List<Integer> findStoredSeqs(@Param("sessionId") String sessionId, @Param("seqs") Collection<Integer> seqs);

    /** Admin export: received in [from, to), in arrival order. */
    List<ProductEventRow> findByReceivedAtGreaterThanEqualAndReceivedAtLessThanOrderByReceivedAtAscIdAsc(Instant from, Instant to);

    @Query("select count(distinct e.sessionId) from ProductEventRow e where e.receivedAt >= :from and e.receivedAt < :to")
    long countSessions(@Param("from") Instant from, @Param("to") Instant to);

    /** The funnel's events received in [from, to): sessionId, name, screen, at, seq, propsJson. */
    @Query("select e.sessionId, e.name, e.screen, e.at, e.seq, e.propsJson from ProductEventRow e "
            + "where e.receivedAt >= :from and e.receivedAt < :to and e.name in :names")
    List<Object[]> findFunnelRows(@Param("from") Instant from, @Param("to") Instant to, @Param("names") Collection<String> names);

    /** When each session's last event in [from, to) came in: sessionId, max(receivedAt) (the funnel's drop analysis). */
    @Query("select e.sessionId, max(e.receivedAt) from ProductEventRow e "
            + "where e.receivedAt >= :from and e.receivedAt < :to group by e.sessionId")
    List<Object[]> findLastReceivedAt(@Param("from") Instant from, @Param("to") Instant to);
}
