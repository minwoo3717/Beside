package com.example.mockbackend.api.v1.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Stage durations in ms. See JobResponseMapper.timings for the exact semantics. */
@Schema(description = "단계별 소요 시간(ms). queuedMs: PENDING 경과, processingMs: PROCESSING 전 null, totalMs: 종료 전 null.")
public record Timings(
        long queuedMs,
        @Schema(nullable = true) Long processingMs,
        @Schema(nullable = true) Long totalMs) {
}
