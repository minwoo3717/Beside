package com.example.mockbackend.repository;

import com.example.mockbackend.domain.Job;
import java.util.Optional;

public interface JobRepository {
    Job save(Job job);
    Optional<Job> findById(String id);
}
