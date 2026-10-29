package com.example.mockbackend.api.v1.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Contract v1 JobQueue: where a PENDING job stands in the GPU queue (QueueEstimator). */
@Schema(description = "대기열 위치(status=PENDING 일 때만). 앱은 \"앞에 {ahead}명\" 처럼 보여 준다. "
        + "real 은 GPU 1장에서 한 번에 1건씩, 접수(또는 retry) 순서로 처리한다.")
public record JobQueue(
        @Schema(minimum = "0", description = "이 작업보다 먼저 처리될 작업 수. 지금 생성 중(PROCESSING)인 작업을 포함한다. 0 이면 바로 다음 차례.")
        int ahead,
        @Schema(nullable = true, example = "INFERENCE_OFFLINE",
                description = "평소보다 오래 기다릴 수 있는 이유: INFERENCE_OFFLINE(GPU 추론 서비스가 꺼져 있거나 모델을 불러오는 중) 또는 null. 모르는 값은 무시한다.")
        String reason) {
}
