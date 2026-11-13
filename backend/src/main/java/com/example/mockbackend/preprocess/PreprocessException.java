package com.example.mockbackend.preprocess;

/**
 * The preprocessing engine failed (a model run, a session, memory): a fault of the server, not of the photo. The upload
 * check lets such a photo through and records it (checkResult SKIPPED:ERROR); the cutout falls back to the original.
 */
public class PreprocessException extends Exception {
    public PreprocessException(String message) {
        super(message);
    }

    public PreprocessException(String message, Throwable cause) {
        super(message, cause);
    }
}
