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
    PAYLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE),
    JOB_NOT_FOUND(HttpStatus.NOT_FOUND),
    ASSET_NOT_FOUND(HttpStatus.NOT_FOUND),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    JOB_NOT_COMPLETED(HttpStatus.CONFLICT),
    JOB_NOT_FAILED(HttpStatus.CONFLICT),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR),

    // --- Job failure codes (JobResponse.error) ---
    INFERENCE_FAILED(null),
    INFERENCE_TIMEOUT(null),
    INFERENCE_UNAVAILABLE(null),
    CONVERSION_FAILED(null);

    private final HttpStatus httpStatus;

    ErrorCode(HttpStatus httpStatus) {
        this.httpStatus = httpStatus;
    }

    /** HTTP status for ErrorResponse codes; null for job failure codes. */
    public HttpStatus httpStatus() {
        return httpStatus;
    }
}
