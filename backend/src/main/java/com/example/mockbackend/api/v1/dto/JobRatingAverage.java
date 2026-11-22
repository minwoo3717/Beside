package com.example.mockbackend.api.v1.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Arithmetic mean of each METRICS §4 item over a job's ratings (not rounded) and how many raters there are. */
@Schema(description = "한 작업의 항목별 산술 평균(소수, 반올림하지 않음)과 평가자 수.")
public record JobRatingAverage(
        @Schema(description = "평가자 수 (= items 길이)") int raters,
        double faceShape,
        double ears,
        double bodyProportion,
        double furColor,
        double pattern,
        double textureArtifacts) {
}
