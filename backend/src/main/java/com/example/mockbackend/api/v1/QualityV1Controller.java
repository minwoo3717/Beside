package com.example.mockbackend.api.v1;

import com.example.mockbackend.api.v1.dto.ErrorResponse;
import com.example.mockbackend.api.v1.dto.JobRating;
import com.example.mockbackend.api.v1.dto.JobRatingList;
import com.example.mockbackend.api.v1.dto.JobRatingRequest;
import com.example.mockbackend.service.JobPhotoService;
import com.example.mockbackend.service.RatingService;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The job detail web page's API (docs/api/openapi.yaml tag quality): the uploaded photos next to the model, and the
 * human quality ratings of docs/METRICS.md §4. Not for the app. Caddy Basic auth guards these routes in deployment
 * (/api/v1/jobs/{@literal *}/photos/{@literal *}, /api/v1/jobs/{@literal *}/ratings{@literal *}); Spring has no auth code.
 * Method names are the operationIds (JobV1ContractTest).
 */
@RestController
@RequestMapping(ApiV1Paths.BASE)
@RequiredArgsConstructor
@Tag(name = "quality", description = "작업 상세 웹 페이지(/job/?id=)의 입력 사진과 3D 품질 사람 평가. 팀 내부용, 배포에서는 Caddy Basic 인증 뒤")
public class QualityV1Controller {
    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final String CACHE_PHOTO = "private, max-age=3600";

    private final JobPhotoService photoService;
    private final RatingService ratingService;

    @Operation(operationId = "downloadJobPhoto", summary = "작업에 올린 사진 원본 (작업 상세 웹 페이지용)",
            description = "index 는 uploadedFiles 순서(0부터). Content-Type 은 저장된 파일명 확장자로(jpg·jpeg·png·webp 밖은 octet-stream).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "사진 바이트",
                    headers = {
                            @Header(name = "Cache-Control", description = CACHE_PHOTO, schema = @Schema(type = "string")),
                            @Header(name = "Content-Length", description = "파일 크기", schema = @Schema(type = "integer", format = "int64")),
                            @Header(name = "X-Content-Type-Options", description = "nosniff", schema = @Schema(type = "string"))},
                    content = {
                            @Content(mediaType = MediaType.IMAGE_JPEG_VALUE, schema = @Schema(type = "string", format = "binary")),
                            @Content(mediaType = MediaType.IMAGE_PNG_VALUE, schema = @Schema(type = "string", format = "binary")),
                            @Content(mediaType = "image/webp", schema = @Schema(type = "string", format = "binary")),
                            @Content(mediaType = MediaType.APPLICATION_OCTET_STREAM_VALUE, schema = @Schema(type = "string", format = "binary"))}),
            @ApiResponse(responseCode = "400", description = "정수가 아닌 index (INVALID_REQUEST)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "없는 작업 또는 없는 사진 (JOB_NOT_FOUND, NOT_FOUND)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "서버 오류 (INTERNAL_ERROR)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class)))})
    @GetMapping("/jobs/{jobId}/photos/{index}")
    public ResponseEntity<Resource> downloadJobPhoto(
            @PathVariable("jobId") String jobId,
            @Parameter(description = "사진 순서 0부터") @PathVariable("index") int index) {
        JobPhotoService.Photo photo = photoService.open(jobId, index);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(photo.contentType()))
                .contentLength(photo.bytes())
                .header(HttpHeaders.CACHE_CONTROL, CACHE_PHOTO)
                .header("X-Content-Type-Options", "nosniff")
                .body(new FileSystemResource(photo.file()));
    }

    @Operation(operationId = "listJobRatings", summary = "작업의 품질 평가 목록과 항목별 평균",
            description = "items 는 평가자 이름 오름차순. 평가가 없으면 items 빈 배열, average null.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "평가 목록과 평균",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = JobRatingList.class))),
            @ApiResponse(responseCode = "404", description = "없는 작업 (JOB_NOT_FOUND)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "서버 오류 (INTERNAL_ERROR)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class)))})
    @GetMapping(value = "/jobs/{jobId}/ratings", produces = JSON)
    public JobRatingList listJobRatings(@PathVariable("jobId") String jobId) {
        return ratingService.list(jobId);
    }

    @Operation(operationId = "putJobRating", summary = "평가자 한 명의 품질 평가를 저장한다 (같은 평가자는 덮어쓰기)",
            description = "COMPLETED 작업만. 6항목 1~5 정수 필수, comment 선택 ≤500자. 검사 순서: 이름·본문(400) → 작업(404) → 상태(409).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "저장된 평가",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = JobRating.class))),
            @ApiResponse(responseCode = "400", description = "평가자 이름·점수·comment·본문 형식 (INVALID_REQUEST)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "없는 작업 (JOB_NOT_FOUND)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "아직 완료되지 않음 (JOB_NOT_COMPLETED)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "서버 오류 (INTERNAL_ERROR)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class)))})
    @PutMapping(value = "/jobs/{jobId}/ratings/{rater}", consumes = JSON, produces = JSON)
    public JobRating putJobRating(
            @PathVariable("jobId") String jobId,
            @Parameter(description = "평가자 이름. 앞뒤 공백을 뺀 1~40자, / \\ ; 제어 문자 금지") @PathVariable("rater") String rater,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = JobRatingRequest.class)))
            @RequestBody JsonNode body) {
        JobRatingRequest request = JobRatingRequest.from(body);
        return ratingService.put(jobId, rater, request);
    }
}
