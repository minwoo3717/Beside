package com.example.mockbackend.repository;

import com.example.mockbackend.domain.JobRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Per-run measurements (docs/METRICS.md §1.1). Production only inserts (JobFinisher); the query is for tests and inspection. */
public interface JobRunRepository extends JpaRepository<JobRun, String> {

    List<JobRun> findByJobIdOrderByAttemptAsc(String jobId);
}
