package com.example.mockbackend.api.v1.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Server-side completion of the jobs created in the funnel's range, by where each job is now (docs/api/openapi.yaml
 * AdminJobCompletion, 2026-11-27). created = completed + failed + canceled + inProgress.
 */
@Schema(description = "(2026-11-27) 서버 작업 기준 완료율. createdAt 이 [from, to) 인 작업을 지금 상태로 센다.")
public record AdminJobCompletion(
        @Schema(description = "기간 안에 만든 작업 수(펫과 함께 지운 작업은 빠짐)") long created,
        @Schema(description = "지금 COMPLETED") long completed,
        @Schema(description = "지금 FAILED(취소 제외)") long failed,
        @Schema(description = "지금 FAILED 이고 error.code CANCELED") long canceled,
        @Schema(description = "지금 PENDING 또는 PROCESSING") long inProgress,
        @Schema(description = "completed ÷ (created − canceled) × 100 (분모 0 이면 0)") double completionPct) {
}
