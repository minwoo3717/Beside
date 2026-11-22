package com.example.mockbackend.api.v1.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** GET /api/v1/admin/stats. How the numbers are computed: the adminStats description in docs/api/openapi.yaml. */
@Schema(description = "modelVersion × workerType 묶음별 통계. 계산 방법은 adminStats 설명.")
public record AdminStats(List<AdminStatsGroup> groups) {
}
