package com.example.mockbackend.api.v1.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** GET /api/v1/admin/ratings/summary: one group per modelVersion, in modelVersion order. */
@Schema(description = "GET /api/v1/admin/ratings/summary. 계산 방법은 adminRatingSummary 설명.")
public record AdminRatingSummary(List<AdminRatingSummaryGroup> groups) {
}
