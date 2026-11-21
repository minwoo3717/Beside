package com.example.mockbackend.api.v1.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** AdminOverview.disk: the file system that holds the storage folder. The folder's location is never exposed. */
@Schema(description = "저장소(storage 폴더)가 있는 파일시스템. 폴더 위치는 응답에 넣지 않는다.")
public record AdminDisk(
        @Schema(description = "이 프로세스가 쓸 수 있는 남은 바이트") long usableBytes,
        @Schema(description = "파일시스템 전체 바이트") long totalBytes,
        @Schema(nullable = true, description = "storage 폴더 아래 파일 크기 합. 최대 60초 캐시. 읽지 못하면 null.") Long storageBytes) {
}
