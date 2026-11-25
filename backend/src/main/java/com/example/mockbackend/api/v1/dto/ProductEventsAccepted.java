package com.example.mockbackend.api.v1.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** The 202 of POST /api/v1/events (docs/api/openapi.yaml ProductEventsAccepted). */
@Schema(description = "POST /api/v1/events 의 202 결과.")
public record ProductEventsAccepted(
        @Schema(description = "저장한 이벤트 수(속성 일부만 버린 이벤트 포함)") int accepted,
        @Schema(description = "버린 이벤트 수(모르는 이름·형식 오류·중복·서버 요청 수 상한)") int dropped) {
}
