package com.example.mockbackend.api.v1.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** One drop reason: an event, prop and value seen in dropped sessions (docs/api/openapi.yaml AdminFunnelReason, 2026-11-27). */
@Schema(description = "(2026-11-27) 이탈 사유 한 줄 — 이탈한 세션에 있었던 이벤트의 (이름, 속성, 값).")
public record AdminFunnelReason(
        @Schema(description = "job_cancel_tap, job_failed_shown, photos_selected, app_error") String event,
        @Schema(description = "stage, code, count(값은 0 만), kind") String key,
        @Schema(description = "속성 값(글자로)") String value,
        long sessions) {
}
