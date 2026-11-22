package com.example.mockbackend.api.v1.dto;

import com.example.mockbackend.domain.JobStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/** One line of the dashboard's recent-jobs table. No server paths, no photo names. */
@Schema(description = "대시보드 표의 한 줄. 서버 경로·사진 이름은 포함하지 않는다.")
public record AdminJobSummary(
        @Schema(format = "uuid") String id,
        @Schema(description = "JobResponse.status 와 같은 값. 클라이언트는 알 수 없는 값을 받으면 크래시 대신 UNKNOWN 으로 처리한다.")
        JobStatus status,
        Instant createdAt,
        @Schema(description = "1 = 첫 실행, retry 마다 +1") int attempt,
        Timings timings,
        @Schema(nullable = true, description = "FAILED 일 때 Job 실패 코드, 그 외 null") String errorCode,
        @Schema(nullable = true, description = "마지막으로 끝난 실행(attempt 가 가장 큰 job_runs 행)의 modelVersion. 없으면 null.")
        String modelVersion) {
}
