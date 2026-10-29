package com.example.mockbackend.api.v1.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Contract v1 JobEstimate: a PENDING or PROCESSING job's remaining time, an estimate (QueueEstimator). */
@Schema(description = "남은 시간 추정치(status=PENDING·PROCESSING 일 때만). 최근 완료 실행(같은 워커, 패스스루 제외, 최근 20건)의 "
        + "processingMs 중앙값 기준, GPU 1장 기준. 보장값이 아니다.")
public record JobEstimate(
        @Schema(nullable = true, minimum = "0", description = "이 작업이 끝날 때까지 남은 시간 추정(ms). 계산할 수 없으면 null.")
        Long remainingMs,
        @Schema(minimum = "1", description = "추정의 근거가 된 최근 완료 실행 수(최대 20).")
        int basedOnRuns) {
}
