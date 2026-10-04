package com.example.mockbackend.service;

import com.example.mockbackend.exception.ErrorCode;

/**
 * Why a real run ends FAILED: a job failure code from docs/api/ERROR_CODES.md, a developer message for
 * JobResponse.error.message (the app shows the ERROR_CODES copy instead), and the details for the server log.
 * Responses never name server paths or internal hosts (CLAUDE.md), so paths, URLs and the inference service's own
 * message stay in {@link #logDetail()}.
 */
public class InferenceFailure extends Exception {
    private final ErrorCode code;
    private final String logDetail;

    public InferenceFailure(ErrorCode code, String message, String logDetail) {
        super(message);
        this.code = code;
        this.logDetail = logDetail;
    }

    public ErrorCode code() {
        return code;
    }

    public String logDetail() {
        return logDetail;
    }
}
