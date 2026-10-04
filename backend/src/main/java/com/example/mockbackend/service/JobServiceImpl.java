package com.example.mockbackend.service;

import com.example.mockbackend.config.RequestStartFilter;
import com.example.mockbackend.domain.AssetVariant;
import com.example.mockbackend.domain.Job;
import com.example.mockbackend.domain.JobStatus;
import com.example.mockbackend.domain.StoredUpload;
import com.example.mockbackend.exception.ApiException;
import com.example.mockbackend.exception.ErrorCode;
import com.example.mockbackend.repository.IdempotencyKeyEntry;
import com.example.mockbackend.repository.IdempotencyKeyRepository;
import com.example.mockbackend.repository.JobRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Job lifecycle: store uploads, create the job, hand it to the active JobWorker (mock | real).
 * v0 and v1 share storage and the worker; only validation and response shapes differ.
 */
@Service
@RequiredArgsConstructor
public class JobServiceImpl implements JobService {
    static final int MAX_PHOTOS = 10;
    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 100;
    static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;
    static final Set<String> ALLOWED_IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/webp");

    private final JobRepository jobRepository;
    private final IdempotencyKeyRepository idempotencyKeys;
    private final JobWorker jobWorker;
    private final Logger log = LoggerFactory.getLogger(JobServiceImpl.class);

    @Value("${storage.upload.dir:storage/uploads}")
    private String uploadDir;

    @PostConstruct
    public void init() throws IOException {
        Files.createDirectories(Path.of(uploadDir));
        Files.createDirectories(Path.of("storage/results"));
    }

    // ------------------------------------------------------------------ v0 (frozen)

    @Override
    public Job createJob(Job job) {
        return jobRepository.save(job);
    }

    @Override
    public Job getJob(String id) {
        return jobRepository.findById(id).orElse(null);
    }

    /**
     * v0: store uploaded files and start the worker. No type/count validation (frozen behaviour).
     */
    public Job submitJob(List<MultipartFile> photos) throws IOException {
        if (photos == null || photos.isEmpty()) {
            throw new IllegalArgumentException("No files uploaded");
        }
        return store(photos);
    }

    // ------------------------------------------------------------------ v1

    @Override
    public Job submitJobV1(List<MultipartFile> photos, String idempotencyKey) {
        if (photos == null || photos.isEmpty()) {
            throw ApiException.badRequest(ErrorCode.NO_PHOTOS, "At least one photo is required in the 'photos' part");
        }
        if (photos.size() > MAX_PHOTOS) {
            throw ApiException.badRequest(ErrorCode.TOO_MANY_PHOTOS,
                    "At most " + MAX_PHOTOS + " photos per job, got " + photos.size());
        }
        for (MultipartFile file : photos) {
            if (file.isEmpty()) {
                throw ApiException.badRequest(ErrorCode.INVALID_REQUEST, "Empty file: " + safeName(file));
            }
            String type = file.getContentType();
            if (type == null || !ALLOWED_IMAGE_TYPES.contains(type.toLowerCase(Locale.ROOT))) {
                throw ApiException.badRequest(ErrorCode.UNSUPPORTED_IMAGE_TYPE, "Unsupported Content-Type '" + type
                        + "' for " + safeName(file) + "; allowed: image/jpeg, image/png, image/webp");
            }
        }
        boolean keyed = idempotencyKey != null && !idempotencyKey.isBlank();
        if (keyed) {
            if (idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
                throw ApiException.badRequest(ErrorCode.INVALID_REQUEST,
                        "Idempotency-Key must be 1~" + MAX_IDEMPOTENCY_KEY_LENGTH + " characters");
            }
            Optional<Job> replay = findJobForKey(idempotencyKey);
            if (replay.isPresent()) {
                log.info("Idempotency-Key replay -> jobId={}", replay.get().getId());
                return replay.get();
            }
        }
        Job job;
        try {
            job = store(photos);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to store uploads", e);
        }
        return keyed ? linkIdempotencyKey(idempotencyKey, job) : job;
    }

    @Override
    public Job getJobOrThrow(String id) {
        return jobRepository.findById(id).orElseThrow(() -> ApiException.jobNotFound(id));
    }

    @Override
    public Job retry(String id) {
        Job job = getJobOrThrow(id);
        if (job.getStatus() != JobStatus.FAILED) {
            throw ApiException.conflict(ErrorCode.JOB_NOT_FAILED, id,
                    "Only FAILED jobs can be retried; current status is " + job.getStatus());
        }
        Instant now = Instant.now();
        job.setErrorCode(null);
        job.setErrorMessage(null);
        job.setResultPath(null);
        job.setHairResultPath(null);
        job.setProcessingStartedAt(null);
        job.setFinishedAt(null);
        job.setProgress(0.0);
        job.setAttempt(job.getAttempt() + 1);
        job.setQueuedAt(now);
        job.setUpdatedAt(now);
        job.setStatus(JobStatus.PENDING);
        jobRepository.save(job);
        jobWorker.dispatch(id);
        log.info("Job retried: {} attempt={}", id, job.getAttempt());
        return job;
    }

    @Override
    public JobPage listJobs(Integer limit, String cursor) {
        int size = limit == null ? DEFAULT_LIMIT : limit;
        if (size < 1 || size > MAX_LIMIT) {
            throw ApiException.badRequest(ErrorCode.INVALID_REQUEST, "limit must be between 1 and " + MAX_LIMIT);
        }
        List<Job> all = jobRepository.findAllNewestFirst();
        int start = 0;
        if (cursor != null && !cursor.isBlank()) {
            int index = -1;
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).getId().equals(cursor)) {
                    index = i;
                    break;
                }
            }
            if (index < 0) {
                throw ApiException.badRequest(ErrorCode.INVALID_REQUEST, "Unknown cursor");
            }
            start = index + 1;
        }
        int end = Math.min(all.size(), start + size);
        List<Job> items = List.copyOf(all.subList(start, end));
        String nextCursor = end < all.size() && !items.isEmpty() ? items.get(items.size() - 1).getId() : null;
        return new JobPage(items, nextCursor);
    }

    @Override
    public Path resolveAsset(String id, AssetVariant variant) {
        Job job = getJobOrThrow(id);
        if (job.getStatus() != JobStatus.COMPLETED) {
            throw ApiException.conflict(ErrorCode.JOB_NOT_COMPLETED, id,
                    "Asset is available only when status is COMPLETED; current status is " + job.getStatus());
        }
        String path = variant == AssetVariant.hair ? job.getHairResultPath() : job.getResultPath();
        if (path == null || !Files.isRegularFile(Path.of(path))) {
            throw ApiException.assetNotFound(id, variant.name());
        }
        return Path.of(path);
    }

    // ------------------------------------------------------------------ Idempotency-Key

    /** The job already created for this key, if any. A key whose job no longer exists is released. */
    private Optional<Job> findJobForKey(String key) {
        Optional<IdempotencyKeyEntry> entry = idempotencyKeys.findById(key);
        if (entry.isEmpty()) {
            return Optional.empty();
        }
        Optional<Job> job = jobRepository.findById(entry.get().getJobId());
        if (job.isEmpty()) {
            idempotencyKeys.deleteById(key);
        }
        return job;
    }

    /** Insert-only: when a concurrent request linked the key first, answer with that request's job. */
    private Job linkIdempotencyKey(String key, Job job) {
        try {
            idempotencyKeys.saveAndFlush(new IdempotencyKeyEntry(key, job.getId(), Instant.now()));
            return job;
        } catch (DataIntegrityViolationException raced) {
            Optional<Job> first = findJobForKey(key);
            log.info("Idempotency-Key raced: jobId={} answers instead of {}", first.map(Job::getId).orElse("?"), job.getId());
            return first.orElse(job);
        }
    }

    // ------------------------------------------------------------------ shared

    private Job store(List<MultipartFile> photos) throws IOException {
        Job job = new Job();
        String jobId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        job.setId(jobId);
        job.setStatus(JobStatus.PENDING);
        job.setCreatedAt(now);
        job.setUpdatedAt(now);
        job.setQueuedAt(now);
        job.setProgress(0.0);
        job.setAttempt(1);

        List<StoredUpload> uploads = new ArrayList<>();
        for (MultipartFile file : photos) {
            String name = safeName(file);
            Path target = uniqueTarget(Path.of(uploadDir), jobId + "_" + name);
            Files.copy(file.getInputStream(), target);
            uploads.add(new StoredUpload(name, file.getSize(), target.toString()));
        }
        job.setUploads(uploads);
        job.setResultPath(null);
        job.setUploadMs(RequestStartFilter.elapsedMs());
        jobRepository.save(job);

        // Invoke another Spring bean so @Async runs through its proxy.
        jobWorker.dispatch(jobId);
        log.info("Job created: {} status={} worker={}", jobId, job.getStatus(), jobWorker.type());
        return job;
    }

    /** Original filename without any directory part; "upload" when the client sent none. */
    private static String safeName(MultipartFile file) {
        String original = file.getOriginalFilename();
        if (original == null || original.isBlank()) {
            return "upload";
        }
        String normalized = original.replace('\\', '/');
        String base = normalized.substring(normalized.lastIndexOf('/') + 1);
        return base.isBlank() ? "upload" : base;
    }

    /** Appends -2, -3, ... when two uploads of one job share a filename. */
    private static Path uniqueTarget(Path dir, String filename) {
        Path target = dir.resolve(filename);
        int dot = filename.lastIndexOf('.');
        String stem = dot > 0 ? filename.substring(0, dot) : filename;
        String ext = dot > 0 ? filename.substring(dot) : "";
        for (int i = 2; Files.exists(target); i++) {
            target = dir.resolve(stem + "-" + i + ext);
        }
        return target;
    }
}
