package com.example.mockbackend.api.v1.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** AdminOverview.queue: jobs waiting for and holding the worker right now. */
@Schema(description = "대기열. pending·processing 은 statusCounts 의 PENDING·PROCESSING 과 같다.")
public record AdminQueue(
        @Schema(description = "PENDING(대기) 작업 수") long pending,
        @Schema(description = "PROCESSING(생성 중) 작업 수") long processing,
        @Schema(nullable = true, description = "가장 오래 기다린 PENDING 작업의 지금까지 queuedMs. 없으면 null.") Long oldestPendingMs,
        @Schema(nullable = true, description = "가장 오래 생성 중인 PROCESSING 작업의 지금까지 processingMs. 없으면 null.") Long oldestProcessingMs) {
}
