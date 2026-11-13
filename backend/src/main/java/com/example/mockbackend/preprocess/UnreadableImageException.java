package com.example.mockbackend.preprocess;

/**
 * The photo itself cannot be used as an image: not a format this server reads, too many pixels, broken data, or a
 * decode that ran past its time limit. A property of the photo (the check answers UNREADABLE_IMAGE), not a fault of
 * the server.
 */
public class UnreadableImageException extends Exception {
    public UnreadableImageException(String message) {
        super(message);
    }

    public UnreadableImageException(String message, Throwable cause) {
        super(message, cause);
    }
}
