package com.example.mockbackend.api.v1.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Map;

/** GET /api/v1/admin/overview (docs/api/openapi.yaml AdminOverview). No server paths. */
@Schema(description = "운영 현황. 서버 경로는 포함하지 않는다.")
public record AdminOverview(
        @Schema(description = "서버가 이 응답을 만든 시각 (ISO 8601, UTC)") Instant generatedAt,
        @Schema(description = "활성 Spring profile (HealthResponse.profile 과 같다)", example = "real,prod") String profile,
        @Schema(description = "워커 종류 (mock | real)", example = "real") String workerType,
        @Schema(description = "상태별 작업 수. JobStatus 의 모든 값이 0 이어도 키로 있다. 모르는 키는 무시한다.")
        Map<String, Long> statusCounts,
        AdminQueue queue,
        AdminDisk disk,
        @Schema(nullable = true, description = "추론 서비스(GPU) 상태. 지금은 항상 null — GPU 상태 감시(PLAN S3)를 병합할 때 "
                + "InferenceStatusSource 빈으로 채운다.")
        Object inference) {
}
