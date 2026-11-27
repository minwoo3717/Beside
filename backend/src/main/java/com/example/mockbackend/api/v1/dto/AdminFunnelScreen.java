package com.example.mockbackend.api.v1.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** The last screen of dropped sessions (docs/api/openapi.yaml AdminFunnelScreen, 2026-11-27). */
@Schema(description = "(2026-11-27) 이탈한 세션의 마지막 화면 하나.")
public record AdminFunnelScreen(
        @Schema(description = "ProductEvent.screen 값") String screen,
        long sessions) {
}
