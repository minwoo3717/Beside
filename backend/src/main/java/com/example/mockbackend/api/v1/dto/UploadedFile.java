package com.example.mockbackend.api.v1.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Original filename (no directory part) and size of one uploaded photo. */
public record UploadedFile(
        @Schema(description = "클라이언트가 보낸 원본 파일명(경로 제거)") String name,
        long bytes) {
}
