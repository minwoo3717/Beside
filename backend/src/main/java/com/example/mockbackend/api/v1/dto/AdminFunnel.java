package com.example.mockbackend.api.v1.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/** GET /api/v1/admin/funnel (docs/api/openapi.yaml AdminFunnel, docs/METRICS.md §6.2). */
@Schema(description = "GET /api/v1/admin/funnel. 계산 방법은 adminFunnel 설명.")
public record AdminFunnel(
        @Schema(nullable = true, description = "요청한 from(없으면 null)") Instant from,
        @Schema(nullable = true, description = "요청한 to(없으면 null)") Instant to,
        @Schema(description = "기간 안에 이벤트가 하나라도 있는 서로 다른 세션 수") long sessions,
        List<AdminFunnelStep> steps,
        @Schema(description = "(2026-11-27) 이탈한 세션의 마지막 screen_view 화면별 세션 수") List<AdminFunnelScreen> lastScreens,
        @Schema(description = "(2026-11-27) 이탈한 세션에 있었던 이유 이벤트별 세션 수") List<AdminFunnelReason> dropReasons,
        @Schema(description = "(2026-11-27) 저장한 강아지 불러오기 퍼널 5단계") List<AdminFunnelStep> librarySteps,
        AdminJobCompletion jobs) {
}
