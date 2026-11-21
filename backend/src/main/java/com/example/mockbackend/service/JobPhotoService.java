package com.example.mockbackend.service;

import com.example.mockbackend.domain.Job;
import com.example.mockbackend.domain.StoredUpload;
import com.example.mockbackend.exception.ApiException;
import com.example.mockbackend.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The photos a job was created from, for the job detail page (GET /api/v1/jobs/{jobId}/photos/{index}). The type comes
 * from the stored file name's extension and only the three upload types are served as images: outside prod the upload
 * check trusts the declared Content-Type, so a stored "page.html" must never come back as text/html.
 * Neither the storage folder nor the stored file name appears in answers or error messages (only in the server log).
 */
@Service
@RequiredArgsConstructor
public class JobPhotoService {
    private static final Map<String, String> IMAGE_TYPES = Map.of(
            "jpg", MediaType.IMAGE_JPEG_VALUE,
            "jpeg", MediaType.IMAGE_JPEG_VALUE,
            "png", MediaType.IMAGE_PNG_VALUE,
            "webp", "image/webp");

    private final JobService jobService;
    private final Logger log = LoggerFactory.getLogger(JobPhotoService.class);

    /** @throws ApiException 404 JOB_NOT_FOUND, or 404 NOT_FOUND for an index outside the uploads or a file gone from the disk */
    public Photo open(String jobId, int index) {
        Job job = jobService.getJobOrThrow(jobId);
        List<StoredUpload> uploads = job.getUploads();
        if (index < 0 || index >= uploads.size()) {
            throw photoNotFound(jobId, index);
        }
        Path file = Path.of(uploads.get(index).path());
        try {
            if (Files.isRegularFile(file)) {
                return new Photo(file, contentType(file.getFileName().toString()), Files.size(file));
            }
        } catch (IOException e) {
            log.warn("Photo {} of job {} is unreadable: {}", index, jobId, e.toString());
            throw photoNotFound(jobId, index);
        }
        log.warn("Photo {} of job {} is missing from the disk: {}", index, jobId, file);
        throw photoNotFound(jobId, index);
    }

    /** image/jpeg | image/png | image/webp by extension (any case), anything else application/octet-stream. */
    static String contentType(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String extension = dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return IMAGE_TYPES.getOrDefault(extension, MediaType.APPLICATION_OCTET_STREAM_VALUE);
    }

    private static ApiException photoNotFound(String jobId, int index) {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, "Job " + jobId + " has no photo " + index, null);
    }

    /** A stored photo to stream. {@code file} is a server path: never put it into a response. */
    public record Photo(Path file, String contentType, long bytes) {
    }
}
