package com.example.mockbackend.api.v1.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** GET /api/v1/jobs/{jobId}/ratings: every rater's rating (name order) and the per-item mean. */
@Schema(description = "GET /api/v1/jobs/{jobId}/ratings.")
public record JobRatingList(
        @Schema(description = "평가자 이름 오름차순") List<JobRating> items,
        @Schema(nullable = true, description = "평가가 없으면 null.") JobRatingAverage average) {
}
