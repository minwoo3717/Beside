package com.example.mockbackend.api.v1.dto;

import com.example.mockbackend.domain.JobStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/** Contract v1 JobResponse (docs/api/openapi.yaml). Never contains server paths. */
@Schema(description = "작업 상태. 서버 내부 경로는 포함하지 않는다.")
public record JobResponse(
        @Schema(format = "uuid") String id,
        @Schema(description = "PENDING → PROCESSING → COMPLETED | FAILED. 클라이언트는 알 수 없는 값을 받으면 크래시 대신 UNKNOWN 으로 처리한다.")
        JobStatus status,
        @Schema(nullable = true, minimum = "0", maximum = "1", description = "진행률 0~1. 서버가 모르면 null.")
        Double progress,
        Instant createdAt,
        Instant updatedAt,
        Timings timings,
        @Schema(nullable = true, description = "COMPLETED 일 때 기본(base) 에셋 정보, 그 외 null.")
        AssetInfo asset,
        @Schema(nullable = true, description = "FAILED 일 때 실패 원인, 그 외 null.")
        JobError error,
        @Schema(description = "업로드된 사진의 원본 파일명과 크기.")
        List<UploadedFile> uploadedFiles) {
}
