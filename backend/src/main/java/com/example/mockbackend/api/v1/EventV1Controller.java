package com.example.mockbackend.api.v1;

import com.example.mockbackend.api.v1.dto.ErrorResponse;
import com.example.mockbackend.api.v1.dto.ProductEventBatch;
import com.example.mockbackend.api.v1.dto.ProductEventsAccepted;
import com.example.mockbackend.exception.ApiException;
import com.example.mockbackend.exception.ErrorCode;
import com.example.mockbackend.service.ProductEventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;

/**
 * Product events (docs/api/openapi.yaml tag events, docs/METRICS.md §6, REVIEW_CHECKLIST #24): the Unity app and the share
 * viewer post batches of screen views and taps. Public in deployment (Caddy lets POST /api/v1/events through without
 * Basic auth); reading them back is the admin API. The body is read here with a hard 32 KB cap instead of being bound by
 * Jackson, so an oversized body is a 413 before it is parsed and a bad event only drops itself (ProductEventService).
 * Any Content-Type is read as JSON (a browser beacon may say text/plain). Method name = operationId (JobV1ContractTest).
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "events", description = "제품 이벤트(클릭·노출·퍼널) 수집. 공개 POST, 읽기는 관리 API")
public class EventV1Controller {
    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;

    private final ProductEventService productEvents;

    @Operation(operationId = "recordEvents", summary = "제품 이벤트 묶음을 보낸다 (앱·공유 뷰어, 공개)",
            description = "1~50개, 본문 32 KB 이하. 묶음 오류는 400, 큰 본문은 413, 잘못된 이벤트·속성은 버리고 센다(docs/METRICS.md §6).",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ProductEventBatch.class))))
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "받음. accepted = 저장한 이벤트 수, dropped = 버린 이벤트 수",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ProductEventsAccepted.class))),
            @ApiResponse(responseCode = "400", description = "잘못된 이벤트 묶음 (INVALID_REQUEST)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "413", description = "본문이 32 KB 초과 (PAYLOAD_TOO_LARGE)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "서버 오류 (INTERNAL_ERROR)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class)))})
    @PostMapping(value = ApiV1Paths.EVENTS, produces = JSON)
    public ResponseEntity<ProductEventsAccepted> recordEvents(HttpServletRequest request) throws IOException {
        return ResponseEntity.status(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON)
                .body(productEvents.ingest(readBody(request)));
    }

    /** At most {@link ProductEventService#MAX_BODY_BYTES}: a declared or actual larger body is 413 PAYLOAD_TOO_LARGE. */
    private static byte[] readBody(HttpServletRequest request) throws IOException {
        int limit = ProductEventService.MAX_BODY_BYTES;
        if (request.getContentLengthLong() > limit) {
            throw tooLarge();
        }
        try (InputStream in = request.getInputStream()) {
            byte[] body = in.readNBytes(limit + 1);
            if (body.length > limit) {
                throw tooLarge();
            }
            return body;
        }
    }

    private static ApiException tooLarge() {
        return new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, ErrorCode.PAYLOAD_TOO_LARGE,
                "Event batch too large: at most " + ProductEventService.MAX_BODY_BYTES + " bytes; send fewer events per batch", null);
    }
}
