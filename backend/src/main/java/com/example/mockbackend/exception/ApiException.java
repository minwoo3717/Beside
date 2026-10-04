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

    public ApiException(HttpStatus status, ErrorCode code, String message, String jobId) {
        super(message);
        this.status = status;
        this.code = code;
        this.jobId = jobId;
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
}
