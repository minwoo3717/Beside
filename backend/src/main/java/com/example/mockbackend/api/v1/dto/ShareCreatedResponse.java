package com.example.mockbackend.api.v1.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/** Body of 201 POST /api/v1/jobs/{jobId}/shares. The info path of the same token is in the Location header. */
@Schema(description = "새 공유 링크. 같은 토큰의 정보 조회 경로는 Location 헤더.")
public record ShareCreatedResponse(
        @Schema(description = "128비트 난수의 base64url(패딩 없음) 22자", example = "q3Zr8yT0bWm1xK4vL9pN2A") String token,
        @Schema(description = "웹 뷰어 페이지, 서버 루트 기준 상대 경로 /s/{token}", example = "/s/q3Zr8yT0bWm1xK4vL9pN2A") String url,
        @Schema(description = "만료 시각 (ISO 8601, UTC)") Instant expiresAt) {
}
