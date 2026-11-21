package com.example.mockbackend.api.v1.dto;

import com.example.mockbackend.domain.PetSpecies;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/** Contract PetResponse (docs/api/openapi.yaml, tag pets). */
@Schema(description = "보관함의 반려동물 한 마리(tag pets).")
public record PetResponse(
        @Schema(format = "uuid") String id,
        @Schema(minLength = 1, maxLength = 40) String name,
        @Schema(description = "종. 클라이언트는 알 수 없는 값을 받으면 크래시 대신 UNKNOWN 으로 처리한다.") PetSpecies species,
        @Schema(nullable = true, maxLength = 500, description = "메모. 없으면 null.") String note,
        Instant createdAt,
        @Schema(description = "이름·종·메모·대표를 마지막으로 바꾼 시각") Instant updatedAt,
        @Schema(description = "이 펫에 들어 있는 작업 수(상태와 관계없이)") int jobCount,
        @Schema(nullable = true, format = "uuid", description = "대표 모델: 고른 작업이 아직 이 펫의 COMPLETED 작업이면 그것, "
                + "아니면 가장 최근 COMPLETED 작업, 없으면 null") String coverJobId,
        @Schema(nullable = true, description = "대표 작업의 썸네일 상대 경로. 대표나 썸네일이 없으면 null") String coverThumbnailUrl,
        @Schema(nullable = true, description = "이 펫의 가장 최근 작업의 createdAt. 작업이 없으면 null") Instant latestJobAt) {
}
