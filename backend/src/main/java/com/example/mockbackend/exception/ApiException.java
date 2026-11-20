package com.example.mockbackend.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Thrown by the v1 service layer; GlobalExceptionHandler turns it into an ErrorResponse envelope.
 */
@Getter
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final ErrorCode code;
    private final String jobId;
    /** Seconds for the Retry-After header (v1 responses only); null = no header. */
    private final Integer retryAfterSeconds;

    public ApiException(HttpStatus status, ErrorCode code, String message, String jobId) {
        this(status, code, message, jobId, null);
    }

    private ApiException(HttpStatus status, ErrorCode code, String message, String jobId, Integer retryAfterSeconds) {
        super(message);
        this.status = status;
        this.code = code;
        this.jobId = jobId;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public static ApiException badRequest(ErrorCode code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message, null);
    }

    public static ApiException jobNotFound(String jobId) {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCode.JOB_NOT_FOUND, "Job not found: " + jobId, jobId);
    }

    public static ApiException assetNotFound(String jobId, String variant) {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCode.ASSET_NOT_FOUND,
                "No '" + variant + "' asset for job " + jobId, jobId);
    }

    public static ApiException conflict(ErrorCode code, String jobId, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message, jobId);
    }

    /**
     * 503 QUEUE_FULL with Retry-After (REVIEW_CHECKLIST #21, proposal before the contract meeting).
     *
     * @param jobId the job of a refused retry (ERROR_CODES.md jobId rule, D7 proposal); null for a refused create
     */
    public static ApiException queueFull(String jobId, int retryAfterSeconds) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.QUEUE_FULL,
                "Generation queue is full; retry after " + retryAfterSeconds + " s", jobId, retryAfterSeconds);
    }
}
