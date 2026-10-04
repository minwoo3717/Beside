package com.example.mockbackend.api.v1;

import com.example.mockbackend.api.v1.dto.AssetInfo;
import com.example.mockbackend.api.v1.dto.JobError;
import com.example.mockbackend.api.v1.dto.JobResponse;
import com.example.mockbackend.api.v1.dto.Timings;
import com.example.mockbackend.api.v1.dto.UploadedFile;
import com.example.mockbackend.domain.AssetVariant;
import com.example.mockbackend.domain.Job;
import com.example.mockbackend.domain.JobStatus;
import com.example.mockbackend.domain.JobTimings;
import com.example.mockbackend.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.io.File;
import java.time.Instant;
import java.util.List;

/**
 * Job (internal) → JobResponse (contract v1). Never copies server paths.
 */
@Component
public class JobResponseMapper {
    public static final String GLB_CONTENT_TYPE = "model/gltf-binary";

    public JobResponse toResponse(Job job) {
        return toResponse(job, Instant.now());
    }

    JobResponse toResponse(Job job, Instant now) {
        return new JobResponse(
                job.getId(),
                job.getStatus(),
                job.getProgress(),
                job.getCreatedAt(),
                job.getUpdatedAt(),
                timings(job, now),
                asset(job),
                error(job),
                job.getUploads().stream().map(u -> new UploadedFile(u.name(), u.bytes())).toList());
    }

    /** Same numbers as events.jsonl: see JobTimings for the semantics. */
    static Timings timings(Job job, Instant now) {
        JobTimings timings = JobTimings.of(job, now);
        return new Timings(timings.queuedMs(), timings.processingMs(), timings.totalMs());
    }

    static AssetInfo asset(Job job) {
        if (job.getStatus() != JobStatus.COMPLETED || job.getResultPath() == null) {
            return null;
        }
        long bytes = new File(job.getResultPath()).length(); // 0 when missing (or the 0-byte sample)
        return new AssetInfo(ApiV1Paths.asset(job.getId(), AssetVariant.base), bytes, AssetVariant.base, GLB_CONTENT_TYPE);
    }

    static JobError error(Job job) {
        if (job.getStatus() != JobStatus.FAILED) {
            return null;
        }
        String code = job.getErrorCode() != null ? job.getErrorCode() : ErrorCode.INTERNAL_ERROR.name();
        return new JobError(code, job.getErrorMessage() != null ? job.getErrorMessage() : "");
    }
}
