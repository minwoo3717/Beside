package com.example.mockbackend.api.v1;

import com.example.mockbackend.api.v1.dto.AdminFunnel;
import com.example.mockbackend.api.v1.dto.AdminProductEvent;
import com.example.mockbackend.api.v1.dto.ErrorResponse;
import com.example.mockbackend.service.Csv;
import com.example.mockbackend.service.ProductEventService;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Admin API for product events (docs/api/openapi.yaml tag admin, docs/METRICS.md §6.2·§6.4): the export and the session
 * funnel shown on /admin. Read-only and team-internal: Caddy Basic auth guards /api/v1/admin/* in deployment, Spring has
 * no auth code. A controller of its own so the existing admin controllers stay as they are. Method names = operationIds.
 */
@RestController
@RequestMapping(ApiV1Paths.BASE + "/admin")
@RequiredArgsConstructor
@Tag(name = "admin", description = "운영 대시보드(/admin)와 측정 내보내기. 팀 내부용, 배포에서는 Caddy Basic 인증 뒤")
public class AdminEventsV1Controller {
    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final String CSV = "text/csv";
    private static final MediaType CSV_UTF8 = MediaType.parseMediaType("text/csv; charset=UTF-8");

    private final ProductEventService productEvents;

    @Operation(operationId = "adminEvents", summary = "제품 이벤트 내보내기 — JSON 또는 CSV",
            description = "열 순서는 openapi adminEvents(receivedAt … props). from·to 는 receivedAt 기준, props 는 CSV 에서 JSON 글.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "이벤트 행. format 에 따라 JSON 배열 또는 CSV.",
                    headers = @Header(name = "Content-Disposition", description = "format=csv 일 때만 attachment; filename=\"product_events.csv\"",
                            schema = @Schema(type = "string")),
                    content = {
                            @Content(mediaType = JSON, array = @ArraySchema(schema = @Schema(implementation = AdminProductEvent.class))),
                            @Content(mediaType = CSV, schema = @Schema(type = "string"))}),
            @ApiResponse(responseCode = "400", description = "잘못된 format·from·to (INVALID_REQUEST)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "서버 오류 (INTERNAL_ERROR)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class)))})
    @GetMapping("/events")
    public ResponseEntity<?> adminEvents(
            @Parameter(description = "json(기본) | csv, 대소문자 무시", schema = @Schema(type = "string", allowableValues = {"json", "csv"}, defaultValue = "json")) @RequestParam(value = "format", required = false, defaultValue = "json") String format,
            @Parameter(description = "receivedAt 하한(포함), ISO 8601 instant") @RequestParam(value = "from", required = false) String from,
            @Parameter(description = "receivedAt 상한(제외), ISO 8601 instant") @RequestParam(value = "to", required = false) String to) {
        boolean csv = Csv.isCsvFormat(format);
        List<AdminProductEvent> rows = productEvents.export(from, to);
        if (!csv) {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(rows);
        }
        return ResponseEntity.ok()
                .contentType(CSV_UTF8)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("product_events.csv").build().toString())
                .body(productEvents.exportCsv(rows));
    }

    @Operation(operationId = "adminFunnel", summary = "세션 퍼널 — 단계별 세션 수·전환율·단계 사이 시간",
            description = "home → photos_selected → generate_tap → model_ready → ar → model_placed, 세션마다 앞 단계 뒤의 첫 이벤트(docs/METRICS.md §6.2).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "단계별 퍼널",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = AdminFunnel.class))),
            @ApiResponse(responseCode = "400", description = "잘못된 from·to (INVALID_REQUEST)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "서버 오류 (INTERNAL_ERROR)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class)))})
    @GetMapping(value = "/funnel", produces = JSON)
    public AdminFunnel adminFunnel(
            @Parameter(description = "receivedAt 하한(포함), ISO 8601 instant") @RequestParam(value = "from", required = false) String from,
            @Parameter(description = "receivedAt 상한(제외), ISO 8601 instant") @RequestParam(value = "to", required = false) String to) {
        return productEvents.funnel(from, to);
    }
}
