package com.example.mockbackend.api.v1.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * One job_runs row (one attempt) for GET /api/v1/admin/runs?format=json. Components are declared in the column order
 * of docs/METRICS.md §1.1, which is also the CSV header order (AdminService.RUN_COLUMNS); Jackson keeps that order.
 * Names follow METRICS and change only through the contract meeting.
 */
@Schema(description = "job_runs 한 행 = 한 실행(attempt). 이름과 순서는 docs/METRICS.md §1.1(CSV 헤더와 같다).")
public record AdminRun(
        @Schema(format = "uuid") String jobId,
        @Schema(description = "1 = 첫 실행, retry 마다 +1") Integer attempt,
        Instant finishedAt,
        @Schema(description = "mock | real") String workerType,
        @Schema(description = "COMPLETED | FAILED. 클라이언트는 알 수 없는 값을 받으면 크래시 대신 UNKNOWN 으로 처리한다.") String status,
        @Schema(nullable = true, description = "FAILED 일 때 Job 실패 코드, 그 외 null") String errorCode,
        @Schema(nullable = true) Long uploadBytes,
        @Schema(nullable = true) Long uploadMs,
        @Schema(nullable = true) Long queuedMs,
        @Schema(nullable = true) Long processingMs,
        @Schema(nullable = true) Long totalMs,
        @Schema(nullable = true) Long inferMs,
        @Schema(nullable = true) Long gpuPeakMB,
        @Schema(nullable = true) Long modelParams,
        @Schema(nullable = true) Long outputVertices,
        @Schema(nullable = true) Long outputTriangles,
        @Schema(nullable = true) Long convertMs,
        @Schema(nullable = true) Long glbBytes,
        @Schema(nullable = true, description = "true 면 추론 서비스가 모델 대신 샘플을 돌려준 실행 — 측정에서 뺀다") Boolean passthrough,
        @Schema(nullable = true) String modelVersion) {
}
