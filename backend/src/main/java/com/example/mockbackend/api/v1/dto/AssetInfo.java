package com.example.mockbackend.api.v1.dto;

import com.example.mockbackend.domain.AssetVariant;
import io.swagger.v3.oas.annotations.media.Schema;

/** Downloadable GLB (docs/asset/GLB_SPEC.md). {@code url} is relative to the server root. */
@Schema(description = "다운로드 가능한 GLB 정보. url 은 서버 루트 기준 상대 경로.")
public record AssetInfo(
        @Schema(example = "/api/v1/jobs/{jobId}/asset?variant=base") String url,
        @Schema(description = "파일 크기(바이트)") long bytes,
        @Schema(description = "base | hair. 클라이언트는 알 수 없는 값을 받으면 크래시 대신 UNKNOWN 으로 처리한다.") AssetVariant variant,
        @Schema(example = "model/gltf-binary") String contentType) {
}
