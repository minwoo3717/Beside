package com.example.mockbackend.api.v1.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** Result of POST /api/v1/admin/client-metrics. Lines in {@code errors} were not stored; the others were or were duplicates. */
@Schema(description = "POST /api/v1/admin/client-metrics 결과.")
public record AdminClientMetricImport(
        @Schema(description = "새로 저장한 행 수") int imported,
        @Schema(description = "(jobId, device, e2eMs) 가 이미 있어 건너뛴 행 수") int skipped,
        @Schema(description = "저장하지 못한 줄. 줄 번호 오름차순.") List<AdminImportError> errors) {
}
