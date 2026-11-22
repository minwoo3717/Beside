package com.example.mockbackend.api.v1.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The ratings of one modelVersion: means over every rating row of the group (not a mean of per-job means), so a job
 * rated by more people weighs more. The baseline-vs-improvement table of the stage-5 report.
 */
@Schema(description = "modelVersion 한 묶음의 평가 평균.")
public record AdminRatingSummaryGroup(
        @Schema(description = "마지막 실행의 modelVersion. null(또는 빈 값)·실행 없음은 (none).", example = "animallift-baseline@3f2a1c9") String modelVersion,
        @Schema(description = "평가된 서로 다른 작업 수") long jobs,
        @Schema(description = "평가 행 수") long ratings,
        @Schema(description = "서로 다른 평가자 수") long raters,
        double faceShape,
        double ears,
        double bodyProportion,
        double furColor,
        double pattern,
        double textureArtifacts) {
}
