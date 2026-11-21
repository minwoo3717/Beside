package com.example.mockbackend.api.v1.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** One rejected line of an imported file. */
@Schema(description = "가져오지 못한 줄.")
public record AdminImportError(
        @Schema(description = "파일의 줄 번호(헤더 = 1). 따옴표 안 줄바꿈으로 여러 줄인 행은 시작 줄.") int line,
        @Schema(description = "개발자용 설명(영문)") String reason) {
}
