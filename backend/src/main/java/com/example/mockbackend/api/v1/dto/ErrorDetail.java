package com.example.mockbackend.api.v1.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Error body. {@code code} is an ErrorCode name (docs/api/ERROR_CODES.md). */
public record ErrorDetail(
        @Schema(example = "JOB_NOT_COMPLETED", description = "ERROR_CODES.md 의 HTTP 오류 코드. 앱은 code 로 문구를 고른다.") String code,
        @Schema(description = "개발자용 설명(영문). 사용자에게 그대로 보여주지 않는다.") String message,
        @Schema(nullable = true, description = "오류가 특정 작업에 관한 것이면 그 jobId") String jobId) {
}
