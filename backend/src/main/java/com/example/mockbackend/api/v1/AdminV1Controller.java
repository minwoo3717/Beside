package com.example.mockbackend.api.v1;

import com.example.mockbackend.api.v1.dto.AdminJobList;
import com.example.mockbackend.api.v1.dto.AdminJobSummary;
import com.example.mockbackend.api.v1.dto.AdminOverview;
import com.example.mockbackend.api.v1.dto.AdminRun;
import com.example.mockbackend.api.v1.dto.AdminStats;
import com.example.mockbackend.api.v1.dto.ErrorResponse;
import com.example.mockbackend.exception.ApiException;
import com.example.mockbackend.exception.ErrorCode;
import com.example.mockbackend.service.AdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

/**
 * Admin API (docs/api/openapi.yaml tag admin): the dashboard at /admin and the measurement export of METRICS §1.1.
 * Team-internal and read-only. Caddy Basic auth guards /api/v1/admin/* in deployment; Spring has no auth code.
 * Method names are the operationIds; JobV1ContractTest compares the generated document with the contract.
 */
@RestController
@RequestMapping(ApiV1Paths.BASE + "/admin")
@RequiredArgsConstructor
@Tag(name = "admin", description = "운영 대시보드(/admin)와 측정 내보내기. 팀 내부용, 배포에서는 Caddy Basic 인증 뒤")
public class AdminV1Controller {
    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final String CSV = "text/csv";
    private static final MediaType CSV_UTF8 = MediaType.parseMediaType("text/csv; charset=UTF-8");

    private final AdminService adminService;

    @Operation(operationId = "adminOverview", summary = "운영 현황 — 상태별 작업 수, 대기열, 디스크, GPU")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "현황",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = AdminOverview.class))),
            @ApiResponse(responseCode = "500", description = "서버 오류 (INTERNAL_ERROR)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class)))})
    @GetMapping(value = "/overview", produces = JSON)
    public AdminOverview adminOverview() {
        return adminService.overview();
    }

    @Operation(operationId = "adminRuns", summary = "실행별 측정표(job_runs) 내보내기 — JSON 또는 CSV",
            description = "열 이름·순서는 docs/METRICS.md §1.1. 기본은 패스스루 제외, finishedAt 오름차순.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "실행 행. format 에 따라 JSON 배열 또는 CSV.",
                    headers = @Header(name = "Content-Disposition", description = "format=csv 일 때만 attachment; filename=\"job_runs.csv\"",
                            schema = @Schema(type = "string")),
                    content = {
                            @Content(mediaType = JSON, array = @ArraySchema(schema = @Schema(implementation = AdminRun.class))),
                            @Content(mediaType = CSV, schema = @Schema(type = "string"))}),
            @ApiResponse(responseCode = "400", description = "잘못된 format·from·to·includePassthrough (INVALID_REQUEST)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "서버 오류 (INTERNAL_ERROR)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class)))})
    @GetMapping("/runs")
    public ResponseEntity<?> adminRuns(
            @Parameter(description = "json(기본) | csv, 대소문자 무시", schema = @Schema(type = "string", allowableValues = {"json", "csv"}, defaultValue = "json")) @RequestParam(value = "format", required = false, defaultValue = "json") String format,
            @Parameter(description = "finishedAt 하한(포함), ISO 8601 instant") @RequestParam(value = "from", required = false) String from,
            @Parameter(description = "finishedAt 상한(제외), ISO 8601 instant") @RequestParam(value = "to", required = false) String to,
            @Parameter(description = "이 modelVersion 만. (none) = 값 없음") @RequestParam(value = "modelVersion", required = false) String modelVersion,
            @Parameter(description = "패스스루 행 포함, 기본 false") @RequestParam(value = "includePassthrough", required = false, defaultValue = "false") boolean includePassthrough,
            @Parameter(description = "mock | real") @RequestParam(value = "workerType", required = false) String workerType) {
        boolean csv = isCsv(format);
        List<AdminRun> runs = adminService.runs(from, to, modelVersion, includePassthrough, workerType);
        if (!csv) {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(runs);
        }
        return ResponseEntity.ok()
                .contentType(CSV_UTF8)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("job_runs.csv").build().toString())
                .body(AdminService.toCsv(runs));
    }

    @Operation(operationId = "adminStats", summary = "modelVersion × workerType 별 성공률과 시간 백분위",
            description = "백분위는 nearest-rank(ceil(p/100 × n) 번째 값), COMPLETED 행 중 값이 있는 행만.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "묶음별 통계",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = AdminStats.class))),
            @ApiResponse(responseCode = "400", description = "잘못된 from·to·includePassthrough (INVALID_REQUEST)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "서버 오류 (INTERNAL_ERROR)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class)))})
    @GetMapping(value = "/stats", produces = JSON)
    public AdminStats adminStats(
            @Parameter(description = "finishedAt 하한(포함), ISO 8601 instant") @RequestParam(value = "from", required = false) String from,
            @Parameter(description = "finishedAt 상한(제외), ISO 8601 instant") @RequestParam(value = "to", required = false) String to,
            @Parameter(description = "패스스루 행 포함, 기본 false") @RequestParam(value = "includePassthrough", required = false, defaultValue = "false") boolean includePassthrough) {
        return adminService.stats(from, to, includePassthrough);
    }

    @Operation(operationId = "adminJobs", summary = "최근 작업 요약 (대시보드 표)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "최근 작업",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = AdminJobList.class))),
            @ApiResponse(responseCode = "400", description = "잘못된 limit (INVALID_REQUEST)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "서버 오류 (INTERNAL_ERROR)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class)))})
    @GetMapping(value = "/jobs", produces = JSON)
    public AdminJobList adminJobs(
            @Parameter(description = "1~100, 기본 20") @RequestParam(value = "limit", required = false) Integer limit) {
        return adminService.recentJobs(limit);
    }

    @Operation(operationId = "adminJob", summary = "작업 하나의 요약 (작업 상세 페이지의 시도 횟수·modelVersion)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "작업 요약",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = AdminJobSummary.class))),
            @ApiResponse(responseCode = "404", description = "없는 작업 (JOB_NOT_FOUND)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "서버 오류 (INTERNAL_ERROR)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class)))})
    @GetMapping(value = "/jobs/{jobId}", produces = JSON)
    public AdminJobSummary adminJob(@PathVariable("jobId") String jobId) {
        return adminService.jobSummary(jobId);
    }

    private static boolean isCsv(String format) {
        String value = format == null ? "json" : format.trim().toLowerCase(Locale.ROOT);
        if (value.equals("csv")) {
            return true;
        }
        if (value.equals("json") || value.isEmpty()) {
            return false;
        }
        throw ApiException.badRequest(ErrorCode.INVALID_REQUEST, "format must be json or csv, got '" + format + "'");
    }
}
