package com.example.mockbackend.api.v1;

import com.example.mockbackend.api.v1.dto.AdminClientMetric;
import com.example.mockbackend.api.v1.dto.AdminClientMetricImport;
import com.example.mockbackend.api.v1.dto.AdminE2eRow;
import com.example.mockbackend.api.v1.dto.AdminRating;
import com.example.mockbackend.api.v1.dto.AdminRatingSummary;
import com.example.mockbackend.api.v1.dto.ErrorResponse;
import com.example.mockbackend.service.ClientMetricService;
import com.example.mockbackend.service.Csv;
import com.example.mockbackend.service.RatingService;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Admin API for the stage-5 report tables (docs/api/openapi.yaml tag admin, docs/METRICS.md §3.1·§4.1): quality
 * ratings with their model version, the app's metrics.csv import and the server + inference + app join. A controller
 * of its own because AdminV1Controller is read-only and this one stores imported files. Team-internal: Caddy Basic auth
 * guards /api/v1/admin/* in deployment; Spring has no auth code. Method names are the operationIds (JobV1ContractTest).
 */
@RestController
@RequestMapping(ApiV1Paths.BASE + "/admin")
@RequiredArgsConstructor
@Tag(name = "admin", description = "운영 대시보드(/admin)와 측정 내보내기. 팀 내부용, 배포에서는 Caddy Basic 인증 뒤")
public class AdminMeasurementV1Controller {
    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final String CSV = "text/csv";
    private static final MediaType CSV_UTF8 = MediaType.parseMediaType("text/csv; charset=UTF-8");

    private final RatingService ratingService;
    private final ClientMetricService clientMetricService;

    @Operation(operationId = "adminRatings", summary = "품질 평가 측정표 — JSON 또는 CSV",
            description = "열 순서는 docs/METRICS.md §4.1. 작업의 마지막 실행의 modelVersion·workerType·passthrough 를 붙인다. 기본 패스스루 제외.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "평가 행. format 에 따라 JSON 배열 또는 CSV.",
                    headers = @Header(name = "Content-Disposition", description = "format=csv 일 때만 attachment; filename=\"job_ratings.csv\"",
                            schema = @Schema(type = "string")),
                    content = {
                            @Content(mediaType = JSON, array = @ArraySchema(schema = @Schema(implementation = AdminRating.class))),
                            @Content(mediaType = CSV, schema = @Schema(type = "string"))}),
            @ApiResponse(responseCode = "400", description = "잘못된 format·from·to·includePassthrough (INVALID_REQUEST)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "서버 오류 (INTERNAL_ERROR)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class)))})
    @GetMapping("/ratings")
    public ResponseEntity<?> adminRatings(
            @Parameter(description = "json(기본) | csv") @RequestParam(value = "format", required = false, defaultValue = "json") String format,
            @Parameter(description = "updatedAt 하한(포함), ISO 8601 instant") @RequestParam(value = "from", required = false) String from,
            @Parameter(description = "updatedAt 상한(제외), ISO 8601 instant") @RequestParam(value = "to", required = false) String to,
            @Parameter(description = "패스스루 실행의 평가 포함, 기본 false") @RequestParam(value = "includePassthrough", required = false, defaultValue = "false") boolean includePassthrough) {
        boolean csv = Csv.isCsvFormat(format);
        List<AdminRating> rows = ratingService.export(from, to, includePassthrough);
        return csv ? csv("job_ratings.csv", RatingService.toCsv(rows)) : json(rows);
    }

    @Operation(operationId = "adminRatingSummary", summary = "modelVersion 별 품질 평가 평균 (보고서의 베이스라인 vs 개선안 표)",
            description = "평균은 평가 행 전체의 산술 평균. ratings = 행 수, raters = 서로 다른 평가자 수, jobs = 서로 다른 작업 수.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "modelVersion 별 평균",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = AdminRatingSummary.class))),
            @ApiResponse(responseCode = "400", description = "잘못된 from·to·includePassthrough (INVALID_REQUEST)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "서버 오류 (INTERNAL_ERROR)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class)))})
    @GetMapping(value = "/ratings/summary", produces = JSON)
    public AdminRatingSummary adminRatingSummary(
            @Parameter(description = "updatedAt 하한(포함), ISO 8601 instant") @RequestParam(value = "from", required = false) String from,
            @Parameter(description = "updatedAt 상한(제외), ISO 8601 instant") @RequestParam(value = "to", required = false) String to,
            @Parameter(description = "패스스루 실행의 평가 포함, 기본 false") @RequestParam(value = "includePassthrough", required = false, defaultValue = "false") boolean includePassthrough) {
        return ratingService.summary(from, to, includePassthrough);
    }

    @Operation(operationId = "adminClientMetrics", summary = "가져온 앱 측정(metrics.csv 행) — JSON 또는 CSV",
            description = "열은 docs/METRICS.md §3 의 metrics.csv 헤더 그대로, 가져온 순서.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "앱 측정 행. format 에 따라 JSON 배열 또는 CSV.",
                    headers = @Header(name = "Content-Disposition", description = "format=csv 일 때만 attachment; filename=\"client_metrics.csv\"",
                            schema = @Schema(type = "string")),
                    content = {
                            @Content(mediaType = JSON, array = @ArraySchema(schema = @Schema(implementation = AdminClientMetric.class))),
                            @Content(mediaType = CSV, schema = @Schema(type = "string"))}),
            @ApiResponse(responseCode = "400", description = "잘못된 format (INVALID_REQUEST)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "서버 오류 (INTERNAL_ERROR)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class)))})
    @GetMapping("/client-metrics")
    public ResponseEntity<?> adminClientMetrics(
            @Parameter(description = "json(기본) | csv") @RequestParam(value = "format", required = false, defaultValue = "json") String format) {
        boolean csv = Csv.isCsvFormat(format);
        List<AdminClientMetric> rows = clientMetricService.list();
        return csv ? csv("client_metrics.csv", ClientMetricService.toCsv(rows)) : json(rows);
    }

    @Operation(operationId = "importClientMetrics", summary = "앱이 폰에 쓴 metrics.csv 를 올려 서버에 저장한다",
            description = "multipart 파트 file. 헤더가 METRICS §3 과 정확히 같아야 한다(아니면 400). 잘못된 줄은 errors 에, (jobId, device, e2eMs) 중복은 skipped.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "가져오기 결과",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = AdminClientMetricImport.class))),
            @ApiResponse(responseCode = "400", description = "파일 없음·빈 파일·헤더 불일치 (INVALID_REQUEST)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "413", description = "파일이 5 MB 초과 (PAYLOAD_TOO_LARGE)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "서버 오류 (INTERNAL_ERROR)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class)))})
    @PostMapping(value = "/client-metrics", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = JSON)
    public AdminClientMetricImport importClientMetrics(
            // Not required for Spring: a missing part must be INVALID_REQUEST, not the NO_PHOTOS of createJob's handler.
            @Parameter(description = "앱의 metrics.csv") @RequestPart(value = "file", required = false) MultipartFile file) {
        return clientMetricService.importCsv(file);
    }

    @Operation(operationId = "adminE2e", summary = "서버·추론·앱 측정을 jobId 로 합친 표 — JSON 또는 CSV",
            description = "앱 측정 한 행마다 그 작업의 마지막 COMPLETED 실행을 붙인다. 열은 METRICS §3.1(서버 uploadMs 는 serverUploadMs).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "결합 행. format 에 따라 JSON 배열 또는 CSV.",
                    headers = @Header(name = "Content-Disposition", description = "format=csv 일 때만 attachment; filename=\"e2e.csv\"",
                            schema = @Schema(type = "string")),
                    content = {
                            @Content(mediaType = JSON, array = @ArraySchema(schema = @Schema(implementation = AdminE2eRow.class))),
                            @Content(mediaType = CSV, schema = @Schema(type = "string"))}),
            @ApiResponse(responseCode = "400", description = "잘못된 format·includePassthrough (INVALID_REQUEST)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "서버 오류 (INTERNAL_ERROR)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class)))})
    @GetMapping("/e2e")
    public ResponseEntity<?> adminE2e(
            @Parameter(description = "json(기본) | csv") @RequestParam(value = "format", required = false, defaultValue = "json") String format,
            @Parameter(description = "패스스루 실행의 행 포함, 기본 false") @RequestParam(value = "includePassthrough", required = false, defaultValue = "false") boolean includePassthrough) {
        boolean csv = Csv.isCsvFormat(format);
        List<AdminE2eRow> rows = clientMetricService.e2e(includePassthrough);
        return csv ? csv("e2e.csv", ClientMetricService.e2eCsv(rows)) : json(rows);
    }

    private static ResponseEntity<?> json(Object body) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
    }

    private static ResponseEntity<?> csv(String filename, byte[] body) {
        return ResponseEntity.ok()
                .contentType(CSV_UTF8)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .body(body);
    }
}
