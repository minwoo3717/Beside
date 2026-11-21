package com.example.mockbackend.api.v1.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/** GET /api/v1/shares/{token}: public, so no job id and no server path. */
@Schema(description = "공유 링크 정보(공개). 작업 id·서버 경로는 넣지 않는다.")
public record ShareInfo(
        @Schema(description = "링크 생성 시각 (ISO 8601, UTC)") Instant createdAt,
        @Schema(description = "만료 시각 (ISO 8601, UTC)") Instant expiresAt,
        ShareAsset asset) {
}
