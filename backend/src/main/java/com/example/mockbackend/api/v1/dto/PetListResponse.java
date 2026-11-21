package com.example.mockbackend.api.v1.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** GET /api/v1/pets (docs/api/openapi.yaml PetListResponse). Oldest first. */
@Schema(description = "GET /api/v1/pets. 추가한 순서(createdAt 오름차순).")
public record PetListResponse(List<PetResponse> items) {
}
