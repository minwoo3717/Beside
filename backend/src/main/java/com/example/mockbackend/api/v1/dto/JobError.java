package com.example.mockbackend.api.v1.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Why a job FAILED. Codes: docs/api/ERROR_CODES.md (job failure codes). */
@Schema(description = "작업 실패 원인. code 목록과 앱 표시 문구는 docs/api/ERROR_CODES.md.")
public record JobError(
        @Schema(example = "INFERENCE_FAILED") String code,
        @Schema(description = "개발자용 설명(영문). 사용자에게 그대로 보여주지 않는다.") String message) {
}
