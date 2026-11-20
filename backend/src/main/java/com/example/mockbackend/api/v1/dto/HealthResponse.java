package com.example.mockbackend.api.v1.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** GET /api/v1/healthz. */
public record HealthResponse(
        @Schema(example = "ok") String status,
        @Schema(description = "활성 Spring 프로파일을 활성화 순서대로 쉼표로 이은 값(공백 없음). 예 mock, real, 공개 서버는 real,prod."
                + " 활성 프로파일이 없으면 기본 프로파일(mock)", example = "mock") String profile,
        @Schema(description = "작업을 처리하는 워커 종류 (mock | real)", example = "mock") String workerType) {
}
