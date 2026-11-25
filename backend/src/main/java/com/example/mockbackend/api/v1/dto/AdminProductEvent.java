package com.example.mockbackend.api.v1.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Map;

/**
 * One row of GET /api/v1/admin/events (docs/api/openapi.yaml AdminProductEvent). Components in the CSV header order
 * (ProductEventService.EXPORT_COLUMNS), so the JSON names and the CSV share one header.
 */
@Schema(description = "제품 이벤트 내보내기 한 행. 이름과 순서는 adminEvents 설명(CSV 헤더와 같다).")
public record AdminProductEvent(
        @Schema(description = "서버가 받은 시각") Instant receivedAt,
        @Schema(description = "지표 환경(local | prod | loadtest)") String env,
        String platform,
        String appVersion,
        @Schema(format = "uuid") String installId,
        @Schema(format = "uuid") String sessionId,
        Integer seq,
        Instant at,
        String name,
        @Schema(nullable = true) String screen,
        @Schema(nullable = true, format = "uuid") String jobId,
        @Schema(nullable = true, description = "저장한 속성(검사를 통과한 것만). 없으면 null.") Map<String, Object> props) {
}
