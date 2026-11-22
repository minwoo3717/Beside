package com.example.mockbackend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * One rater's quality rating of one completed job, table "job_ratings" (docs/METRICS.md §4.1). A rater rates a job
 * once: saving again overwrites the scores and the comment and moves {@code updatedAt}, so "two or more raters" is
 * "two or more rows". The rater is the trimmed name the web page sends (case-sensitive), not an account.
 * Holds no server paths. New columns must be nullable (boxed) types: ddl-auto=update cannot add a NOT NULL column to
 * a table that has rows.
 */
@Entity
@Table(name = "job_ratings",
        uniqueConstraints = @UniqueConstraint(name = "uk_job_ratings_job_rater", columnNames = {"jobId", "rater"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class JobRating {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 36)
    private String jobId;

    /** Up to 40 code points; 160 UTF-16 units leave room for characters outside the BMP. */
    @Column(nullable = false, length = 160)
    private String rater;

    @Column(nullable = false)
    private Integer faceShape;
    @Column(nullable = false)
    private Integer ears;
    @Column(nullable = false)
    private Integer bodyProportion;
    @Column(nullable = false)
    private Integer furColor;
    @Column(nullable = false)
    private Integer pattern;
    @Column(nullable = false)
    private Integer textureArtifacts;

    /** Up to 500 code points, null when none. */
    @Column(length = 2000)
    private String comment;

    /** First save by this rater. */
    @Column(nullable = false)
    private Instant createdAt;

    /** Last save (overwrite) by this rater. */
    @Column(nullable = false)
    private Instant updatedAt;

    public static JobRating create(String jobId, String rater, RatingScores scores, String comment, Instant now) {
        JobRating rating = new JobRating();
        rating.jobId = jobId;
        rating.rater = rater;
        rating.createdAt = now;
        rating.update(scores, comment, now);
        return rating;
    }

    /** Overwrites every score and the comment (null clears it); createdAt stays. */
    public void update(RatingScores scores, String comment, Instant now) {
        faceShape = scores.faceShape();
        ears = scores.ears();
        bodyProportion = scores.bodyProportion();
        furColor = scores.furColor();
        pattern = scores.pattern();
        textureArtifacts = scores.textureArtifacts();
        this.comment = comment;
        updatedAt = now;
    }

    public RatingScores scores() {
        return new RatingScores(faceShape, ears, bodyProportion, furColor, pattern, textureArtifacts);
    }
}
