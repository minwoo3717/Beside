package com.example.mockbackend.repository;

import com.example.mockbackend.domain.Job;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple in-memory repository using ConcurrentHashMap.
 * Jobs are lost on restart. Switching to an H2 file database is a PLAN stage-0 item (docs/PLAN.md).
 */
@Repository
public class MemoryJobRepository implements JobRepository {
    private static final Comparator<Job> NEWEST_FIRST = Comparator
            .comparing(Job::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(Job::getId, Comparator.reverseOrder());

    private final Map<String, Job> store = new ConcurrentHashMap<>();

    @Override
    public Job save(Job job) {
        store.put(job.getId(), job);
        return job;
    }

    @Override
    public Optional<Job> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public List<Job> findAllNewestFirst() {
        return store.values().stream().sorted(NEWEST_FIRST).toList();
    }
}
