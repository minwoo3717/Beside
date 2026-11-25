package com.example.mockbackend.api.v1;

import com.example.mockbackend.api.v1.dto.ErrorResponse;
import com.example.mockbackend.api.v1.dto.ShareAsset;
import com.example.mockbackend.api.v1.dto.ShareCreatedResponse;
import com.example.mockbackend.api.v1.dto.ShareInfo;
import com.example.mockbackend.api.v1.dto.SharesRevokedResponse;
import com.example.mockbackend.domain.ShareLink;
import com.example.mockbackend.service.ShareService;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * Share links (docs/api/openapi.yaml tag "share"). Creating and revoking all links of a job are called by the job page
 * (/job/?id=, behind the team account in production; the app does not share yet); the three token routes are public and
 * back the web viewer page /s/{token} (SharePageController), so nothing they answer, errors included, carries the job
 * id. Method names are the operationIds (JobV1ContractTest).
 */
@RestController
@RequestMapping(ApiV1Paths.BASE)
@RequiredArgsConstructor
@Tag(name = "share", description = "공유 링크와 공개 웹 3D/AR 뷰어")
public class ShareV1Controller {
    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final String GLB = JobResponseMapper.GLB_CONTENT_TYPE;
    /** Download name without the job id. */
    static final String SHARED_FILENAME = "beside-pet.glb";

    private final ShareService shareService;

    @Operation(operationId = "createShare", summary = "완료된 작업의 공유 링크를 만든다",
            description = "COMPLETED 이고 base GLB 가 있는 작업만. 부를 때마다 새 토큰. url 은 웹 뷰어 /s/{token}. "
                    + "작업 하나의 살아 있는 링크는 " + ShareService.MAX_ACTIVE_PER_JOB + "개까지(넘으면 400, revokeJobShares 로 끊는다).")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "공유 링크 생성",
                    headers = @Header(name = "Location", description = "/api/v1/shares/{token}", schema = @Schema(type = "string")),
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ShareCreatedResponse.class))),
            @ApiResponse(responseCode = "400", description = "ttlDays 범위 밖 또는 살아 있는 링크가 이미 상한 (INVALID_REQUEST)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "없는 작업 또는 파일 없음 (JOB_NOT_FOUND, ASSET_NOT_FOUND)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "아직 완료되지 않음 (JOB_NOT_COMPLETED)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "서버 오류 (INTERNAL_ERROR)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class)))})
    @PostMapping(value = "/jobs/{jobId}/shares", produces = JSON)
    public ResponseEntity<ShareCreatedResponse> createShare(
            @PathVariable("jobId") String jobId,
            @Parameter(description = "유효 기간(일) 1~30, 생략 시 share.ttl-days(기본 7)")
            @RequestParam(value = "ttlDays", required = false) Integer ttlDays) {
        ShareLink link = shareService.create(jobId, ttlDays);
        String token = link.getToken();
        return ResponseEntity.created(URI.create(ApiV1Paths.share(token)))
                .body(new ShareCreatedResponse(token, ApiV1Paths.sharePage(token), link.getExpiresAt()));
    }

    @Operation(operationId = "revokeJobShares", summary = "작업의 살아 있는 공유 링크를 모두 끊는다",
            description = "토큰 없이 끊는 길(토큰은 만들 때 한 번만 응답한다). revoked 는 이번에 끊은 수, 없으면 0.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "끊음",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = SharesRevokedResponse.class))),
            @ApiResponse(responseCode = "404", description = "없는 작업 (JOB_NOT_FOUND)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "서버 오류 (INTERNAL_ERROR)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class)))})
    @DeleteMapping(value = "/jobs/{jobId}/shares", produces = JSON)
    public SharesRevokedResponse revokeJobShares(@PathVariable("jobId") String jobId) {
        return new SharesRevokedResponse(shareService.revokeAllForExistingJob(jobId));
    }

    @Operation(operationId = "getShare", summary = "공유 링크 정보 (공개 뷰어용)",
            description = "만료 시각과 GLB 주소. 작업 id 는 넣지 않는다. 없음·만료·취소는 모두 404 SHARE_NOT_FOUND.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "공유 정보",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ShareInfo.class))),
            @ApiResponse(responseCode = "404", description = "공유 링크 없음 (SHARE_NOT_FOUND)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "서버 오류 (INTERNAL_ERROR)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class)))})
    @GetMapping(value = "/shares/{token}", produces = JSON)
    public ShareInfo getShare(@PathVariable("token") String token) {
        ShareService.SharedAsset shared = shareService.view(token); // open(), counted in beside.share.views
        ShareLink link = shared.link();
        return new ShareInfo(link.getCreatedAt(), link.getExpiresAt(),
                new ShareAsset(ApiV1Paths.shareAsset(token), shared.bytes(), GLB));
    }

    @Operation(operationId = "downloadShareAsset", summary = "공유된 GLB (공개 뷰어·AR 용)",
            description = "base GLB. Cache-Control public, max-age 는 3600 과 만료까지 남은 초 중 작은 값.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "GLB 바이너리",
                    headers = {
                            @Header(name = "Cache-Control", description = "public, max-age=N (N ≤ 3600)", schema = @Schema(type = "string")),
                            @Header(name = "Content-Disposition", description = "inline; filename=\"" + SHARED_FILENAME + "\"", schema = @Schema(type = "string")),
                            @Header(name = "Content-Length", description = "파일 크기", schema = @Schema(type = "integer", format = "int64"))},
                    content = @Content(mediaType = GLB, schema = @Schema(type = "string", format = "binary"))),
            @ApiResponse(responseCode = "404", description = "공유 링크 없음 (SHARE_NOT_FOUND)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "서버 오류 (INTERNAL_ERROR)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class)))})
    @GetMapping("/shares/{token}/asset")
    public ResponseEntity<Resource> downloadShareAsset(@PathVariable("token") String token) {
        ShareService.SharedAsset shared = shareService.open(token);
        // No contentLength, as in JobV1Controller.downloadAsset: Spring sets it from the resource for 200 and from the
        // range for a single-range 206; a fixed whole-file length would also go out on a multi-range 206, whose body
        // is shorter (review finding, 2026-11-25).
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(GLB))
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=" + shareService.cacheSeconds(shared.link()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + SHARED_FILENAME + "\"")
                .body(new FileSystemResource(shared.file()));
    }

    @Operation(operationId = "revokeShare", summary = "공유 링크를 취소한다",
            description = "취소 후 정보·에셋 모두 404 SHARE_NOT_FOUND. 이미 취소·만료된 링크도 404.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "취소됨"),
            @ApiResponse(responseCode = "404", description = "공유 링크 없음 (SHARE_NOT_FOUND)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "서버 오류 (INTERNAL_ERROR)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class)))})
    @DeleteMapping("/shares/{token}")
    public ResponseEntity<Void> revokeShare(@PathVariable("token") String token) {
        shareService.revoke(token);
        return ResponseEntity.noContent().build();
    }
}
