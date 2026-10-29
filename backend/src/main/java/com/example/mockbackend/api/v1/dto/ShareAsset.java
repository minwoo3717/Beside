package com.example.mockbackend.api.v1.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** The shared GLB (docs/asset/GLB_SPEC.md). {@code url} is relative to the server root and carries no job id. */
@Schema(description = "공유된 GLB. url 은 서버 루트 기준 상대 경로.")
public record ShareAsset(
        @Schema(example = "/api/v1/shares/q3Zr8yT0bWm1xK4vL9pN2A/asset") String url,
        @Schema(description = "파일 크기(바이트)") long bytes,
        @Schema(example = "model/gltf-binary") String contentType) {
}
