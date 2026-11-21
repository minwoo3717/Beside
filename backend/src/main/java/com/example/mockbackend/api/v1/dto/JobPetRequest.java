package com.example.mockbackend.api.v1.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * PUT /api/v1/jobs/{jobId}/pet body (docs/api/openapi.yaml JobPetRequest): {"petId": "..."} or {"petId": null}.
 * The field must be sent; the setter remembers that it was, so {} (a likely mistake) is not read as "unassign".
 */
@Schema(description = "PUT /api/v1/jobs/{jobId}/pet 본문. petId 필드는 꼭 보낸다(null 이면 미분류로).")
public class JobPetRequest {
    private String petId;
    private boolean petIdSent;

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, format = "uuid",
            description = "넣을 반려동물. null 이면 펫에서 빼서 미분류로.")
    public String getPetId() {
        return petId;
    }

    public void setPetId(String petId) {
        this.petId = petId;
        this.petIdSent = true;
    }

    /** Whether the JSON had a petId field (null counts). Not a bean getter, so not a property. */
    public boolean petIdSent() {
        return petIdSent;
    }
}
