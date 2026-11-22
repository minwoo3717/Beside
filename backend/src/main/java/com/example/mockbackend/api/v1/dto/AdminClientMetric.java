package com.example.mockbackend.api.v1.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One imported line of the app's metrics.csv. Components in the header order of docs/METRICS.md §3
 * (ClientMetricService.COLUMNS), so the JSON names, the export CSV and the app's file share one header.
 */
@Schema(description = "앱 metrics.csv 한 행. 이름·순서·정의는 docs/METRICS.md §3 (CSV 헤더와 같다).")
public record AdminClientMetric(
        String jobId,
        Long uploadMs,
        Long waitMs,
        Long downloadMs,
        Long loadMs,
        Long e2eMs,
        Double avgFps,
        Double minFps,
        Long memMB,
        @Schema(nullable = true, description = "SystemInfo.deviceModel. 빈 칸이면 null.") String device) {
}
