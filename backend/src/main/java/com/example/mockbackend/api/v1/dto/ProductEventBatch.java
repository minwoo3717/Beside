package com.example.mockbackend.api.v1.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * POST /api/v1/events body (docs/api/openapi.yaml ProductEventBatch): one session's events, 1 to 50, at most 32 KB.
 * Documents the request for springdoc; ProductEventService checks the raw JSON (fields not listed here are never read).
 */
@Schema(description = "POST /api/v1/events 본문. 한 세션의 이벤트 1~50개. 개인을 알아볼 값은 넣지 않는다.")
public record ProductEventBatch(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid", description = "설치마다 한 번 만든 난수 UUID")
        String installId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid", description = "앱 실행(웹은 페이지 열기)마다 새 UUID")
        String sessionId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 32, pattern = "^[0-9A-Za-z._+-]{1,32}$",
                description = "앱 버전, 웹은 web")
        String appVersion,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"android", "web"})
        String platform,
        @ArraySchema(minItems = 1, maxItems = 50, schema = @Schema(implementation = ProductEvent.class))
        List<ProductEvent> events) {
}
