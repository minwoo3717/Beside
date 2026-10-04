package com.example.mockbackend.api.v1.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** GET /api/v1/healthz. */
public record HealthResponse(
        @Schema(example = "ok") String status,
        @Schema(description = "활성 Spring profile (mock | real)", example = "mock") String profile,
        @Schema(description = "작업을 처리하는 워커 종류 (mock | real)", example = "mock") String workerType) {
}
