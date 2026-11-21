package com.example.mockbackend.api.v1.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * PATCH /api/v1/pets/{petId} body (docs/api/openapi.yaml UpdatePetRequest), read like a JSON merge patch: a field that
 * is absent stays as it is, a field sent as null clears it. Jackson calls a setter for every field present in the JSON,
 * null included, so the setters remember which fields were sent.
 */
@Schema(description = "PATCH /api/v1/pets/{petId} 본문. 보낸 필드만 바꾼다(JSON merge patch 처럼).")
public class UpdatePetRequest {
    private String name;
    private String species;
    private String note;
    private String coverJobId;
    private boolean nameSent;
    private boolean speciesSent;
    private boolean noteSent;
    private boolean coverJobIdSent;

    @Schema(minLength = 1, maxLength = 40, description = "앞뒤 공백을 뺀 뒤 1~40자. null 불가.")
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
        this.nameSent = true;
    }

    @Schema(allowableValues = {"DOG", "CAT", "OTHER"}, description = "null 불가.")
    public String getSpecies() {
        return species;
    }

    public void setSpecies(String species) {
        this.species = species;
        this.speciesSent = true;
    }

    @Schema(nullable = true, maxLength = 500, description = "null 이나 빈 값이면 메모를 지운다.")
    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
        this.noteSent = true;
    }

    @Schema(nullable = true, format = "uuid", description = "대표로 고를 이 펫의 COMPLETED 작업. null 이면 자동(가장 최근 COMPLETED 작업).")
    public String getCoverJobId() {
        return coverJobId;
    }

    public void setCoverJobId(String coverJobId) {
        this.coverJobId = coverJobId;
        this.coverJobIdSent = true;
    }

    // Not bean getters (they take no "get"/"is" prefix), so Jackson and springdoc do not see them as properties.

    public boolean nameSent() {
        return nameSent;
    }

    public boolean speciesSent() {
        return speciesSent;
    }

    public boolean noteSent() {
        return noteSent;
    }

    public boolean coverJobIdSent() {
        return coverJobIdSent;
    }
}
