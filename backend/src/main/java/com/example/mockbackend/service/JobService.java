package com.example.mockbackend.service;

import com.example.mockbackend.domain.AssetVariant;
import com.example.mockbackend.domain.Job;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.List;

public interface JobService {
    // --- v0 (frozen) ---
    Job createJob(Job job);

    Job getJob(String id);

    // --- v1 (docs/api/openapi.yaml) ---

    /** Validates per contract (1~10 photos, jpeg/png/webp, non-empty), honours Idempotency-Key, stores and dispatches. */
    Job submitJobV1(List<MultipartFile> photos, String idempotencyKey);

    /** @throws com.example.mockbackend.exception.ApiException 404 JOB_NOT_FOUND */
    Job getJobOrThrow(String id);

    /** FAILED → PENDING and dispatch again. @throws ApiException 409 JOB_NOT_FAILED otherwise */
    Job retry(String id);

    /** Newest first. limit 1~100 (default 20), cursor = id of the last item of the previous page. */
    JobPage listJobs(Integer limit, String cursor);

    /** @throws ApiException 409 JOB_NOT_COMPLETED, 404 ASSET_NOT_FOUND */
    Path resolveAsset(String id, AssetVariant variant);
}
