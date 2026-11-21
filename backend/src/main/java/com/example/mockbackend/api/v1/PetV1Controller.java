package com.example.mockbackend.api.v1;

import com.example.mockbackend.api.v1.dto.CreatePetRequest;
import com.example.mockbackend.api.v1.dto.ErrorResponse;
import com.example.mockbackend.api.v1.dto.JobPetRequest;
import com.example.mockbackend.api.v1.dto.JobResponse;
import com.example.mockbackend.api.v1.dto.PetDetailResponse;
import com.example.mockbackend.api.v1.dto.PetListResponse;
import com.example.mockbackend.api.v1.dto.PetResponse;
import com.example.mockbackend.api.v1.dto.UpdatePetRequest;
import com.example.mockbackend.service.PetService;
import com.example.mockbackend.service.PetWithJobs;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
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

import java.net.URI;

/**
 * The web library (docs/api/openapi.yaml tag pets): pets, moving jobs between them, and deleting a pet with or without
 * its finished jobs (JobDeletionService through PetService). Used by /library (static/library/), not by the app; Caddy
 * Basic auth guards these routes in deployment and Spring has no auth code. Method names are the operationIds
 * (JobV1ContractTest). API v1.2 (REVIEW_CHECKLIST #11, 2026-11-21) did not take the review branch's
 * DELETE /api/v1/jobs/{jobId} and GET /api/v1/jobs/{jobId}/thumbnail.
 */
@RestController
@RequestMapping(ApiV1Paths.BASE)
@RequiredArgsConstructor
@Tag(name = "pets", description = "반려동물 보관함 웹(/library)용. 앱은 쓰지 않는다.")
public class PetV1Controller {
    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;

    private final PetService petService;
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
}
