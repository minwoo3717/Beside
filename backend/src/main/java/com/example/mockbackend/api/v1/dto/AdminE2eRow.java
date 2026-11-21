package com.example.mockbackend.api.v1.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * One app measurement joined with its job's last COMPLETED run (GET /api/v1/admin/e2e). Components in the column order
 * of docs/METRICS.md §3.1 (ClientMetricService.E2E_COLUMNS): METRICS names, except the server's uploadMs, which is
 * serverUploadMs because the app has its own uploadMs. Server and inference cells are null without a run.
 */
@Schema(description = "앱 측정 한 행 + 그 작업의 마지막 COMPLETED 실행. 이름·순서는 adminE2e 설명(CSV 헤더와 같다).")
public record AdminE2eRow(
        String jobId,
        @Schema(nullable = true) String modelVersion,
        @Schema(nullable = true) String workerType,
        @Schema(nullable = true) Boolean passthrough,
        @Schema(nullable = true) Integer attempt,
        @Schema(nullable = true) Instant finishedAt,
        @Schema(nullable = true) Long uploadBytes,
        @Schema(nullable = true, description = "서버 uploadMs(METRICS §1 — 요청 도착 → 사진 저장). 앱 uploadMs 와 다른 값이다.") Long serverUploadMs,
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
        @Schema(description = "앱 uploadMs(METRICS §3 — POST 시작 → 202 수신)") Long uploadMs,
        Long waitMs,
        Long downloadMs,
        Long loadMs,
        Long e2eMs,
        Double avgFps,
        Double minFps,
        Long memMB,
        @Schema(nullable = true) String device) {
}
