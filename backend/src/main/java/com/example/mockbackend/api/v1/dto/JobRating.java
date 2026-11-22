package com.example.mockbackend.api.v1.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * One stored rating (job × rater) as the contract shows it. Same simple name as the entity
 * {@code domain.JobRating} on purpose: DTO class names are the openapi schema names (JobV1ContractTest).
 */
@Schema(description = "저장된 평가 한 건(작업 × 평가자).")
public record JobRating(
        @Schema(format = "uuid") String jobId,
        @Schema(description = "앞뒤 공백을 뺀 평가자 이름") String rater,
        int faceShape,
        int ears,
        int bodyProportion,
        int furColor,
        int pattern,
        int textureArtifacts,
        @Schema(nullable = true) String comment,
        @Schema(description = "이 평가자가 처음 저장한 시각 (ISO 8601, UTC)") Instant createdAt,
        @Schema(description = "마지막으로 저장(덮어쓰기)한 시각 (ISO 8601, UTC)") Instant updatedAt) {
}
