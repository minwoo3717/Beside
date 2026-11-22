package com.example.mockbackend.api.v1.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** POST /api/v1/pets body (docs/api/openapi.yaml CreatePetRequest). Checked by PetService, not by bean validation. */
@Schema(description = "POST /api/v1/pets 본문.")
public record CreatePetRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 40, description = "앞뒤 공백을 뺀 뒤 1~40자")
        String name,
        @Schema(allowableValues = {"DOG", "CAT", "OTHER"}, defaultValue = "DOG")
        String species,
        @Schema(nullable = true, maxLength = 500, description = "500자 이하. 생략하거나 빈 값이면 null.")
        String note) {
}
