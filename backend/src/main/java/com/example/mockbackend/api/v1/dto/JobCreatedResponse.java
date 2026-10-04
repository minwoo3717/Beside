package com.example.mockbackend.api.v1.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Body of 202 responses (create, retry). The same id is in the Location header. */
public record JobCreatedResponse(@Schema(format = "uuid") String jobId) {
}
