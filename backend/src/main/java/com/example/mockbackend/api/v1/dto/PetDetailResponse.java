package com.example.mockbackend.api.v1.dto;

import com.example.mockbackend.domain.PetSpecies;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * GET /api/v1/pets/{petId} (docs/api/openapi.yaml PetDetailResponse): every PetResponse field, then the jobs (newest
 * first) and the modelVersion of each job's last finished run. Flat on purpose (the contract writes it as allOf).
 */
@Schema(description = "GET /api/v1/pets/{petId}. PetResponse 의 모든 필드와 생성 기록.")
public record PetDetailResponse(
        @Schema(format = "uuid") String id,
        String name,
        @Schema(description = "종. 클라이언트는 알 수 없는 값을 받으면 크래시 대신 UNKNOWN 으로 처리한다.") PetSpecies species,
        @Schema(nullable = true) String note,
        Instant createdAt,
        Instant updatedAt,
        int jobCount,
        @Schema(nullable = true, format = "uuid") String coverJobId,
        @Schema(nullable = true) Instant latestJobAt,
        @Schema(description = "이 펫의 작업, createdAt 최신순.") List<JobResponse> jobs,
        @Schema(description = "jobId → 마지막으로 끝난 실행의 modelVersion. 없으면 키가 없다.") Map<String, String> modelVersions) {

    public static PetDetailResponse of(PetResponse pet, List<JobResponse> jobs, Map<String, String> modelVersions) {
        return new PetDetailResponse(pet.id(), pet.name(), pet.species(), pet.note(), pet.createdAt(), pet.updatedAt(),
                pet.jobCount(), pet.coverJobId(), pet.latestJobAt(), jobs, modelVersions);
    }
}
