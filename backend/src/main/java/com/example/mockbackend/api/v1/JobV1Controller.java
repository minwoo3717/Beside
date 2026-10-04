package com.example.mockbackend.api.v1;

import com.example.mockbackend.api.v1.dto.ErrorResponse;
import com.example.mockbackend.api.v1.dto.HealthResponse;
import com.example.mockbackend.api.v1.dto.JobCreatedResponse;
import com.example.mockbackend.api.v1.dto.JobListResponse;
import com.example.mockbackend.api.v1.dto.JobResponse;
import com.example.mockbackend.domain.AssetVariant;
import com.example.mockbackend.domain.Job;
import com.example.mockbackend.service.JobPage;
import com.example.mockbackend.service.JobService;
import com.example.mockbackend.service.JobWorker;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * API v1 — the contract Unity sees. Shape of record: docs/api/openapi.yaml.
 * Method names are the operationIds and @ApiResponses declare the status codes; JobV1ContractTest
 * compares what springdoc generates from here with the contract document.
 */
@RestController
@RequestMapping(ApiV1Paths.BASE)
@RequiredArgsConstructor
@Tag(name = "jobs", description = "3D 생성 작업")
public class JobV1Controller {
    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final String GLB = JobResponseMapper.GLB_CONTENT_TYPE;

    private final JobService jobService;
    private final JobResponseMapper mapper;
    private final JobWorker jobWorker;
    private final Environment environment;

    @Operation(operationId = "createJob", summary = "사진을 업로드해 3D 생성 작업을 만든다",
            description = "multipart/form-data 의 photos 파트로 1~10장 (image/jpeg|png|webp, 장당 ≤5MB, 전체 ≤20MB). 접수 즉시 202, 처리는 비동기.")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "작업 접수",
                    headers = @Header(name = "Location", description = "/api/v1/jobs/{jobId}", schema = @Schema(type = "string")),
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = JobCreatedResponse.class))),
            @ApiResponse(responseCode = "400", description = "잘못된 요청 (INVALID_REQUEST, NO_PHOTOS, TOO_MANY_PHOTOS, UNSUPPORTED_IMAGE_TYPE)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "413", description = "용량 초과 (PAYLOAD_TOO_LARGE)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "서버 오류 (INTERNAL_ERROR)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class)))})
    @PostMapping(value = "/jobs", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = JSON)
    public ResponseEntity<JobCreatedResponse> createJob(
            @Parameter(description = "반려동물 사진 1~10장") @RequestPart("photos") List<MultipartFile> photos,
            @Parameter(name = "Idempotency-Key", in = ParameterIn.HEADER, description = "선택. 같은 키의 재요청에 같은 jobId 를 돌려준다")
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        Job job = jobService.submitJobV1(photos, idempotencyKey);
        return accepted(job);
    }

    @Operation(operationId = "listJobs", summary = "작업 목록 (개발·디버그용)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "작업 목록",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = JobListResponse.class))),
            @ApiResponse(responseCode = "400", description = "잘못된 limit/cursor (INVALID_REQUEST)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "서버 오류 (INTERNAL_ERROR)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class)))})
    @GetMapping(value = "/jobs", produces = JSON)
    public JobListResponse listJobs(
            @Parameter(description = "페이지 크기 1~100, 기본 20") @RequestParam(value = "limit", required = false) Integer limit,
            @Parameter(description = "이전 응답의 nextCursor") @RequestParam(value = "cursor", required = false) String cursor) {
        JobPage page = jobService.listJobs(limit, cursor);
        return new JobListResponse(page.items().stream().map(mapper::toResponse).toList(), page.nextCursor());
    }

    @Operation(operationId = "getJob", summary = "작업 상태 조회 (폴링)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "작업 상태",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = JobResponse.class))),
            @ApiResponse(responseCode = "404", description = "없는 작업 (JOB_NOT_FOUND)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "서버 오류 (INTERNAL_ERROR)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class)))})
    @GetMapping(value = "/jobs/{jobId}", produces = JSON)
    public JobResponse getJob(@PathVariable("jobId") String jobId) {
        return mapper.toResponse(jobService.getJobOrThrow(jobId));
    }

    @Operation(operationId = "downloadAsset", summary = "생성된 GLB 다운로드",
            description = "COMPLETED 인 작업의 GLB. variant 생략 시 base. 파일 계약은 docs/asset/GLB_SPEC.md.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "GLB 바이너리",
                    headers = {
                            @Header(name = "Content-Disposition", description = "attachment; filename=\"{jobId}-{variant}.glb\"", schema = @Schema(type = "string")),
                            @Header(name = "Content-Length", description = "파일 크기", schema = @Schema(type = "integer", format = "int64"))},
                    content = @Content(mediaType = GLB, schema = @Schema(type = "string", format = "binary"))),
            @ApiResponse(responseCode = "400", description = "잘못된 variant (INVALID_REQUEST)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "없는 작업 또는 없는 variant (JOB_NOT_FOUND, ASSET_NOT_FOUND)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "아직 완료되지 않음 (JOB_NOT_COMPLETED)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "서버 오류 (INTERNAL_ERROR)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class)))})
    @GetMapping("/jobs/{jobId}/asset")
    public ResponseEntity<Resource> downloadAsset(
            @PathVariable("jobId") String jobId,
            @Parameter(description = "base | hair, 기본 base") @RequestParam(value = "variant", required = false, defaultValue = "base") AssetVariant variant)
            throws IOException {
        Path file = jobService.resolveAsset(jobId, variant);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(GLB))
                .contentLength(Files.size(file))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + jobId + "-" + variant.name() + ".glb\"")
                .body(new FileSystemResource(file));
    }

    @Operation(operationId = "retryJob", summary = "실패한 작업을 같은 사진으로 다시 실행한다")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "재시도 접수, 작업은 PENDING 으로",
                    headers = @Header(name = "Location", description = "/api/v1/jobs/{jobId}", schema = @Schema(type = "string")),
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = JobCreatedResponse.class))),
            @ApiResponse(responseCode = "404", description = "없는 작업 (JOB_NOT_FOUND)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "FAILED 가 아님 (JOB_NOT_FAILED)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "서버 오류 (INTERNAL_ERROR)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class)))})
    @PostMapping(value = "/jobs/{jobId}/retry", produces = JSON)
    public ResponseEntity<JobCreatedResponse> retryJob(@PathVariable("jobId") String jobId) {
        return accepted(jobService.retry(jobId));
    }

    @Operation(operationId = "health", summary = "서버 상태와 활성 프로파일", tags = {"health"})
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "정상",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = HealthResponse.class)))})
    @GetMapping(value = "/healthz", produces = JSON)
    public HealthResponse health() {
        String[] active = environment.getActiveProfiles();
        String profile = String.join(",", active.length > 0 ? active : environment.getDefaultProfiles());
        return new HealthResponse("ok", profile, jobWorker.type());
    }

    private static ResponseEntity<JobCreatedResponse> accepted(Job job) {
        return ResponseEntity.accepted()
                .location(URI.create(ApiV1Paths.job(job.getId())))
                .body(new JobCreatedResponse(job.getId()));
    }
}
