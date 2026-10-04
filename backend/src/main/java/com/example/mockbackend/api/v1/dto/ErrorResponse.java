package com.example.mockbackend.api.v1.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Envelope of every v1 error response: {@code { "error": { "code", "message", "jobId?" } }}. */
@Schema(description = "모든 v1 오류 응답의 공통 봉투")
public record ErrorResponse(ErrorDetail error) {
}
