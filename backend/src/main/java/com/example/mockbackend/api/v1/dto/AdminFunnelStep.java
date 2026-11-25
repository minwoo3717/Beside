package com.example.mockbackend.api.v1.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** One step of the session funnel (docs/api/openapi.yaml AdminFunnelStep). */
@Schema(description = "퍼널 한 단계.")
public record AdminFunnelStep(
        @Schema(description = "단계 이름(home, photos_selected, generate_tap, model_ready, ar, model_placed)") String step,
        @Schema(description = "세는 이벤트, 예 screen_view(screen=ar)") String event,
        long sessions,
        @Schema(description = "첫 단계 세션 대비 % (0~100)") double conversionPct,
        @Schema(nullable = true, description = "바로 앞 단계 세션 대비 %. 첫 단계는 null.") Double fromPrevPct,
        @Schema(nullable = true, description = "앞 단계에서 이 단계까지 걸린 초의 중앙값(nearest-rank). 첫 단계·세션 0 이면 null.")
        Double medianSecondsFromPrev) {
}
