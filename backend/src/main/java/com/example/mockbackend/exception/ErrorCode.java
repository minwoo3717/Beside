package com.example.mockbackend.exception;

import org.springframework.http.HttpStatus;

/**
 * API v1 error codes. Must stay 1:1 with docs/api/ERROR_CODES.md.
 * HTTP codes are returned in ErrorResponse.error.code; job codes appear in JobResponse.error.code when FAILED.
 */
public enum ErrorCode {
    // --- HTTP error codes (ErrorResponse) ---
    INVALID_REQUEST(HttpStatus.BAD_REQUEST),
    NO_PHOTOS(HttpStatus.BAD_REQUEST),
    TOO_MANY_PHOTOS(HttpStatus.BAD_REQUEST),
    UNSUPPORTED_IMAGE_TYPE(HttpStatus.BAD_REQUEST),
    // first-photo check before the 202 (ERROR_CODES.md "대표 사진 검사"), in the order it is checked
    UNREADABLE_IMAGE(HttpStatus.BAD_REQUEST),
    IMAGE_TOO_SMALL(HttpStatus.BAD_REQUEST),
    NO_ANIMAL_DETECTED(HttpStatus.BAD_REQUEST),
    TOO_MANY_ANIMALS(HttpStatus.BAD_REQUEST),
    ANIMAL_TOO_SMALL(HttpStatus.BAD_REQUEST),
    IMAGE_TOO_DARK(HttpStatus.BAD_REQUEST),
    IMAGE_TOO_BLURRY(HttpStatus.BAD_REQUEST),
    PAYLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE),
    JOB_NOT_FOUND(HttpStatus.NOT_FOUND),
    ASSET_NOT_FOUND(HttpStatus.NOT_FOUND),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    /** Public share endpoints: unknown, malformed, expired or revoked token, or the shared job is gone. Never carries a jobId. */
    SHARE_NOT_FOUND(HttpStatus.NOT_FOUND),
    /** Library (tag pets): unknown or malformed petId in the path. Never carries a jobId. */
    PET_NOT_FOUND(HttpStatus.NOT_FOUND),
    JOB_NOT_COMPLETED(HttpStatus.CONFLICT),
    JOB_NOT_FAILED(HttpStatus.CONFLICT),
    /**
     * PENDING jobs plus requests being admitted reached admission.max-pending (off by default). Decided 2026-11-21
     * (REVIEW_CHECKLIST #21): this code and its 503 are API v1.1; production keeps the cap off for now.
     */
    QUEUE_FULL(HttpStatus.SERVICE_UNAVAILABLE),
    JOB_NOT_PENDING(HttpStatus.CONFLICT),
    /** Deleting a PENDING or PROCESSING job (directly or through a pet with withJobs=true): cancel or wait first. */
    JOB_IN_PROGRESS(HttpStatus.CONFLICT),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR),

    // --- Job failure codes (JobResponse.error) ---
    INFERENCE_FAILED(null),
    INFERENCE_TIMEOUT(null),
    INFERENCE_UNAVAILABLE(null),
    CONVERSION_FAILED(null),
    /** The client canceled the job while it was PENDING (POST /cancel); retry starts it again. */
    CANCELED(null);

    private final HttpStatus httpStatus;

    ErrorCode(HttpStatus httpStatus) {
        this.httpStatus = httpStatus;
    }

    /** HTTP status for ErrorResponse codes; null for job failure codes. */
    public HttpStatus httpStatus() {
        return httpStatus;
    }
}
