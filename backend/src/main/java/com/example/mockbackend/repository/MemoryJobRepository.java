package com.example.mockbackend.repository;

import com.example.mockbackend.domain.Job;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple in-memory repository using ConcurrentHashMap.
 */
@Repository
public class MemoryJobRepository implements JobRepository {
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
}
