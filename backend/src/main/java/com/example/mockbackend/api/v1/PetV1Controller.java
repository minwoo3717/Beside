package com.example.mockbackend.api.v1;

import com.example.mockbackend.api.v1.dto.CreatePetRequest;
import com.example.mockbackend.api.v1.dto.ErrorResponse;
import com.example.mockbackend.api.v1.dto.JobPetRequest;
import com.example.mockbackend.api.v1.dto.JobResponse;
import com.example.mockbackend.api.v1.dto.PetDetailResponse;
import com.example.mockbackend.api.v1.dto.PetListResponse;
import com.example.mockbackend.api.v1.dto.PetResponse;
import com.example.mockbackend.api.v1.dto.UpdatePetRequest;
import com.example.mockbackend.exception.ApiException;
import com.example.mockbackend.exception.ErrorCode;
import com.example.mockbackend.service.JobDeletionService;
import com.example.mockbackend.service.JobService;
import com.example.mockbackend.service.PetService;
import com.example.mockbackend.service.PetWithJobs;
import com.example.mockbackend.service.ThumbnailService;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The web library (docs/api/openapi.yaml tag pets): pets, moving jobs between them, deleting finished jobs and job
 * thumbnails. Used by /library (static/library/), not by the app; Caddy Basic auth guards these routes in deployment and
 * Spring has no auth code. Method names are the operationIds (JobV1ContractTest).
 */
@RestController
@RequestMapping(ApiV1Paths.BASE)
@RequiredArgsConstructor
@Tag(name = "pets", description = "반려동물 보관함 웹(/library)용. 앱은 쓰지 않는다.")
public class PetV1Controller {
    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    /** A job's thumbnail never changes; it sits behind Basic auth, so only the browser may keep it. */
    static final String THUMBNAIL_CACHE = "private, max-age=86400";

    private final PetService petService;
    private final JobService jobService;
    private final JobDeletionService jobDeletion;
    private final ThumbnailService thumbnails;
    private final PetResponseMapper petMapper;
    private final JobResponseMapper jobMapper;

    @Operation(operationId = "createPet", summary = "보관함에 반려동물을 추가한다",
            description = "name 은 앞뒤 공백을 뺀 1~40자, species 는 DOG|CAT|OTHER(기본 DOG), note 는 500자 이하. 어기면 400 INVALID_REQUEST.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "추가됨",
                    headers = @Header(name = "Location", description = "/api/v1/pets/{petId}", schema = @Schema(type = "string")),
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = PetResponse.class))),
            @ApiResponse(responseCode = "400", description = "규칙 위반·JSON 아님 (INVALID_REQUEST)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "서버 오류 (INTERNAL_ERROR)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class)))})
    @PostMapping(value = "/pets", produces = JSON)
    public ResponseEntity<PetResponse> createPet(@RequestBody CreatePetRequest body) {
        PetWithJobs pet = petService.create(body);
        return ResponseEntity.created(URI.create(ApiV1Paths.pet(pet.pet().getId()))).body(petMapper.toResponse(pet));
    }

    @Operation(operationId = "listPets", summary = "보관함의 반려동물 목록", description = "추가한 순서. 작업 수·대표 모델·마지막 작업 시각을 함께 준다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "반려동물 목록",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = PetListResponse.class))),
            @ApiResponse(responseCode = "500", description = "서버 오류 (INTERNAL_ERROR)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class)))})
    @GetMapping(value = "/pets", produces = JSON)
    public PetListResponse listPets() {
        return new PetListResponse(petService.list().stream().map(petMapper::toResponse).toList());
    }

    @Operation(operationId = "getPet", summary = "반려동물 한 마리와 생성 기록")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "반려동물과 생성 기록(최신순)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = PetDetailResponse.class))),
            @ApiResponse(responseCode = "404", description = "없는 펫 (PET_NOT_FOUND)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "서버 오류 (INTERNAL_ERROR)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class)))})
    @GetMapping(value = "/pets/{petId}", produces = JSON)
    public PetDetailResponse getPet(@PathVariable("petId") String petId) {
        return petMapper.toDetail(petService.get(petId));
    }

    @Operation(operationId = "updatePet", summary = "이름·종·메모·대표 모델을 바꾼다",
            description = "보낸 필드만 바꾼다. note·coverJobId 는 null 로 지운다. coverJobId 는 이 펫의 COMPLETED 작업만.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "바뀐 반려동물",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = PetResponse.class))),
            @ApiResponse(responseCode = "400", description = "규칙 위반 (INVALID_REQUEST)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "없는 펫 (PET_NOT_FOUND)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "서버 오류 (INTERNAL_ERROR)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class)))})
    @PatchMapping(value = "/pets/{petId}", produces = JSON)
    public PetResponse updatePet(@PathVariable("petId") String petId, @RequestBody UpdatePetRequest body) {
        return petMapper.toResponse(petService.update(petId, body));
    }

    @Operation(operationId = "deletePet", summary = "반려동물을 보관함에서 지운다",
            description = "기본은 작업을 미분류로 되돌린다. withJobs=true 면 작업도 지운다(진행 중 작업이 있으면 아무것도 지우지 않고 409).")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "지움"),
            @ApiResponse(responseCode = "400", description = "잘못된 withJobs (INVALID_REQUEST)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "없는 펫 (PET_NOT_FOUND)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "진행 중 작업 (JOB_IN_PROGRESS)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "서버 오류 (INTERNAL_ERROR)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class)))})
    @DeleteMapping("/pets/{petId}")
    public ResponseEntity<Void> deletePet(
            @PathVariable("petId") String petId,
            @Parameter(description = "true 면 펫의 작업도 지운다. 기본 false")
            @RequestParam(value = "withJobs", required = false, defaultValue = "false") boolean withJobs) {
        petService.delete(petId, withJobs);
        return ResponseEntity.noContent().build();
    }

    @Operation(operationId = "setJobPet", summary = "작업을 반려동물에 넣거나 미분류로 뺀다",
            description = "{\"petId\": \"<uuid>\"} 또는 {\"petId\": null}. 상태와 관계없이 바꿀 수 있다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "바뀐 작업 상태",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = JobResponse.class))),
            @ApiResponse(responseCode = "400", description = "petId 필드 없음·없는 펫 (INVALID_REQUEST)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "없는 작업 (JOB_NOT_FOUND)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "서버 오류 (INTERNAL_ERROR)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class)))})
    @PutMapping(value = "/jobs/{jobId}/pet", produces = JSON)
    public JobResponse setJobPet(@PathVariable("jobId") String jobId, @RequestBody JobPetRequest body) {
        return jobMapper.toResponse(petService.setJobPet(jobId, body));
    }

    @Operation(operationId = "deleteJob", summary = "끝난 작업을 지운다 (보관함 웹)",
            description = "COMPLETED·FAILED 만. 사진·결과 폴더·썸네일을 지우고 공유 링크를 취소하고 Idempotency-Key 를 지운다. job_runs 는 남긴다.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "지움"),
            @ApiResponse(responseCode = "404", description = "없는 작업 (JOB_NOT_FOUND)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "PENDING·PROCESSING (JOB_IN_PROGRESS)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "서버 오류 (INTERNAL_ERROR)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class)))})
    @DeleteMapping("/jobs/{jobId}")
    public ResponseEntity<Void> deleteJob(@PathVariable("jobId") String jobId) {
        jobDeletion.delete(jobId);
        return ResponseEntity.noContent().build();
    }

    @Operation(operationId = "downloadThumbnail", summary = "작업의 썸네일 (첫 사진, JPEG)",
            description = "긴 변 256px JPEG. 썸네일이 없으면 404 ASSET_NOT_FOUND, 없는 작업은 404 JOB_NOT_FOUND.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "JPEG 썸네일",
                    headers = @Header(name = "Cache-Control", description = THUMBNAIL_CACHE, schema = @Schema(type = "string")),
                    content = @Content(mediaType = MediaType.IMAGE_JPEG_VALUE, schema = @Schema(type = "string", format = "binary"))),
            @ApiResponse(responseCode = "404", description = "없는 작업 또는 썸네일 없음 (JOB_NOT_FOUND, ASSET_NOT_FOUND)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "서버 오류 (INTERNAL_ERROR)",
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ErrorResponse.class)))})
    @GetMapping("/jobs/{jobId}/thumbnail")
    public ResponseEntity<Resource> downloadThumbnail(@PathVariable("jobId") String jobId) throws IOException {
        jobService.getJobOrThrow(jobId);
        Path file = thumbnails.find(jobId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                ErrorCode.ASSET_NOT_FOUND, "No thumbnail for job " + jobId + " (its first photo could not be read)", jobId));
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .contentLength(Files.size(file))
                .header(HttpHeaders.CACHE_CONTROL, THUMBNAIL_CACHE)
                .body(new FileSystemResource(file));
    }
}
