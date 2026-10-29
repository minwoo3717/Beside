package com.example.mockbackend.api.v1.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One modelVersion × workerType group. runs/completed/failed count every row except runs the user canceled
 * (FAILED with error code CANCELED, counted apart in canceled); percentiles and averages use the COMPLETED rows that
 * have the value (failed runs would blur the time distribution, METRICS §1.1).
 */
@Schema(description = "modelVersion × workerType 한 묶음.")
public record AdminStatsGroup(
        @Schema(description = "job_runs.modelVersion. null(또는 빈 값)은 (none).", example = "animallift-baseline@3f2a1c9") String modelVersion,
        @Schema(description = "mock | real") String workerType,
        @Schema(description = "행 수 (COMPLETED + FAILED, 취소 제외)") long runs,
        long completed,
        @Schema(description = "FAILED 행 수 (취소 제외)") long failed,
        @Schema(description = "사용자가 취소한 실행 수 (FAILED + error code CANCELED). runs·failed·successRate 에 넣지 않는다") long canceled,
        @Schema(description = "completed / runs (0~1)") double successRate,
        AdminPercentiles queuedMs,
        AdminPercentiles processingMs,
        AdminPercentiles totalMs,
        AdminPercentiles inferMs,
        @Schema(nullable = true, description = "COMPLETED 행의 glbBytes 평균. 값이 있는 행이 없으면 null.") Double avgGlbBytes,
        @Schema(nullable = true, description = "COMPLETED 행의 gpuPeakMB 평균. 값이 있는 행이 없으면 null.") Double avgGpuPeakMB) {
}
