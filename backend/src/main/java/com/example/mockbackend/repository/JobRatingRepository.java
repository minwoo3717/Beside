package com.example.mockbackend.repository;

import com.example.mockbackend.domain.JobRating;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Quality ratings (docs/METRICS.md §4.1), one row per job and rater. Written only by RatingService. */
public interface JobRatingRepository extends JpaRepository<JobRating, Long> {

    Optional<JobRating> findByJobIdAndRater(String jobId, String rater);

    List<JobRating> findByJobId(String jobId);

    /** Ratings with {@code from <= updatedAt < to} (the admin export and summary). */
    @Query("select r from JobRating r where r.updatedAt >= :from and r.updatedAt < :to")
    List<JobRating> findUpdatedBetween(@Param("from") Instant from, @Param("to") Instant to);
}
