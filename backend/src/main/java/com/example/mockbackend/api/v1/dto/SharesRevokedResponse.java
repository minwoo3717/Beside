package com.example.mockbackend.api.v1.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Body of 200 DELETE /api/v1/jobs/{jobId}/shares. */
@Schema(description = "DELETE /api/v1/jobs/{jobId}/shares 의 결과.")
public record SharesRevokedResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0",
                description = "이번에 끊은 살아 있던 링크 수(이미 만료·취소된 링크는 세지 않는다).") int revoked) {
}
