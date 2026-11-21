package com.example.mockbackend.api.v1.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Nearest-rank p50/p95 of one measurement (ms); always one of the measured values, null when n = 0. */
@Schema(description = "한 측정 항목(ms)의 nearest-rank 백분위. COMPLETED 행 중 값이 있는 행만 쓴다.")
public record AdminPercentiles(
        @Schema(description = "계산에 쓴 행 수") long n,
        @Schema(nullable = true) Long p50,
        @Schema(nullable = true) Long p95) {
}
