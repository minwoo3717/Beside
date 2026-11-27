package com.example.mockbackend.api.v1.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Map;

/**
 * One event of a POST /api/v1/events batch (docs/api/openapi.yaml ProductEvent, docs/METRICS.md §6.1). Documents the
 * request for springdoc; the server reads the body as a JSON tree (ProductEventService) so that a wrong value drops one
 * event or one prop instead of refusing the batch. The enums must match ProductEventCatalog.
 */
@Schema(description = "이벤트 하나. 이름별 props 는 docs/METRICS.md §6.1 표.")
public record ProductEvent(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "이벤트 이름. 목록에 없는 이름은 버리고 dropped 로 센다.",
                allowableValues = {"screen_view", "photos_selected", "generate_tap", "job_cancel_tap", "job_failed_shown",
                        "model_ready", "fur_toggle", "ar_issue_shown", "model_placed", "animation_play", "photo_saved",
                        "start_over", "app_error", "saved_dog_open", "saved_dog_change"})
        String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "클라이언트 시계로 일어난 시각 (ISO 8601)")
        Instant at,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0", description = "세션 안에서 0 부터 하나씩. 같은 sessionId·seq 는 한 번만 저장")
        Integer seq,
        @Schema(nullable = true, format = "uuid", description = "관계된 작업(있을 때만)")
        String jobId,
        @Schema(nullable = true, description = "이벤트가 일어난 화면. screen_view 에서는 보여 준 화면(꼭 있어야 함).",
                allowableValues = {"home", "confirm", "progress", "preview", "ar", "error", "settings", "library", "camera", "share"})
        String screen,
        @Schema(nullable = true, description = "이름별 속성(enum·정수·불리언만). 목록 밖의 키·값은 서버가 버린다.")
        Map<String, Object> props) {
}
