package com.example.mockbackend.controller;

import com.example.mockbackend.domain.Job;
import com.example.mockbackend.domain.JobStatus;
import com.example.mockbackend.domain.StoredUpload;

import java.time.Instant;
import java.util.List;

/**
 * v0 response of GET /api/jobs/{id}, frozen. It reproduces the shape from when the Job entity was
 * serialized directly (including server paths). Do not extend; v1 clients use api.v1.dto.JobResponse.
 *
 * @deprecated v0 is kept only for the browser Mock UI until Unity has moved to /api/v1.
 */
@Deprecated
public record LegacyJobResponse(
        String id,
        JobStatus status,
        Instant createdAt,
        Instant updatedAt,
        List<String> uploadedFiles,
        String resultPath,
        long durationMs) {

    public static LegacyJobResponse from(Job job) {
        return new LegacyJobResponse(
                job.getId(),
                job.getStatus(),
                job.getCreatedAt(),
                job.getUpdatedAt(),
                job.getUploads().stream().map(StoredUpload::path).toList(),
                job.getResultPath(),
                job.getDurationMs());
    }
}
