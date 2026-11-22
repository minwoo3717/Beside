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
    private final boolean modelRan;

    /** A failure before or around the model: transport, auth, availability, or Spring's own handling of the result. */
    public InferenceFailure(ErrorCode code, String message, String logDetail) {
        this(code, message, logDetail, false);
    }

    private InferenceFailure(ErrorCode code, String message, String logDetail, boolean modelRan) {
        super(message);
        this.code = code;
        this.logDetail = logDetail;
        this.modelRan = modelRan;
    }

    /**
     * A failure the inference service produced while handling the job itself (its own error answer, a malformed
     * result, no answer within inference.timeout-ms): the run counts against the model it has loaded.
     */
    public static InferenceFailure modelRan(ErrorCode code, String message, String logDetail) {
        return new InferenceFailure(code, message, logDetail, true);
    }

    /** Whether the model ran (or was asked to and failed by itself): job_runs.modelVersion is then the loaded model. */
    public boolean modelRan() {
        return modelRan;
    }

    public ErrorCode code() {
        return code;
    }

    public String logDetail() {
        return logDetail;
    }
}
