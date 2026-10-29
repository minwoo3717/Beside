package com.example.mockbackend.api.v1.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * One row of GET /api/v1/admin/ratings: a rating plus the job's last run (modelVersion, workerType, passthrough).
 * Components are declared in the column order of docs/METRICS.md §4.1, which is also the CSV header order
 * (RatingService.EXPORT_COLUMNS); Jackson keeps that order.
 */
@Schema(description = "품질 평가 측정표 한 행. 이름과 순서는 adminRatings 설명(CSV 헤더와 같다).")
public record AdminRating(
        @Schema(format = "uuid") String jobId,
        @Schema(nullable = true, description = "그 작업의 마지막 실행의 modelVersion. 실행 행이 없거나 값이 없으면 null.") String modelVersion,
        String rater,
        int faceShape,
        int ears,
        int bodyProportion,
        int furColor,
        int pattern,
        int textureArtifacts,
        @Schema(nullable = true) String comment,
        Instant updatedAt,
        @Schema(nullable = true, description = "그 작업의 마지막 실행의 workerType (mock | real). 실행 행이 없으면 null.") String workerType,
        @Schema(nullable = true, description = "그 작업의 마지막 실행의 passthrough. 실행 행이 없거나 값이 없으면 null.") Boolean passthrough) {
}
