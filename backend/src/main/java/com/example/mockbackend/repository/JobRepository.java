package com.example.mockbackend.repository;

import com.example.mockbackend.domain.Job;

import java.util.List;
import java.util.Optional;

public interface JobRepository {
    Job save(Job job);

    Optional<Job> findById(String id);

    /** All jobs, newest first (createdAt desc, then id desc for a stable order). */
    List<Job> findAllNewestFirst();
}
