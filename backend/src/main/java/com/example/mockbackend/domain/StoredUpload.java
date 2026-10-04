package com.example.mockbackend.domain;

/**
 * One uploaded photo. {@code name} is the client's original filename (path stripped);
 * {@code path} is the server storage path and must never appear in API v1 responses.
 */
public record StoredUpload(String name, long bytes, String path) {
}
