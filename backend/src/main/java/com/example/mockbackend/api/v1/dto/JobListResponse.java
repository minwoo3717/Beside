package com.example.mockbackend.api.v1.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** GET /api/v1/jobs page. */
public record JobListResponse(
        List<JobResponse> items,
        @Schema(nullable = true, description = "다음 페이지 cursor. 마지막 페이지면 null.") String nextCursor) {
}
