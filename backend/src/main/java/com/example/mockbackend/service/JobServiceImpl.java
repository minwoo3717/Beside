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
import org.springframework.data.domain.PageRequest;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
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
    /** UTF-8 bytes of the client's name kept in a disk name: {jobId}_ (37) + this + "-N" stays under ext4's 255. */
    static final int MAX_NAME_BYTES = 180;

    private final JobRepository jobRepository;
    private final IdempotencyKeyRepository idempotencyKeys;
    private final JobWorker jobWorker;
    private final JobMetrics jobMetrics;
    private final Logger log = LoggerFactory.getLogger(JobServiceImpl.class);

    @Value("${storage.upload.dir:storage/uploads}")
    private String uploadDir;

    @Value("${storage.result.dir:storage/results}")
    private String resultDir;

    /** prod: v1 photos must start with a JPEG, PNG or WEBP signature (off elsewhere; tests upload placeholder bytes). */
    @Value("${upload.verify-magic:false}")
    private boolean verifyMagic;

    @PostConstruct
    public void init() throws IOException {
        Files.createDirectories(Path.of(uploadDir));
        Files.createDirectories(Path.of(resultDir));
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
            // Fail here rather than after minutes in the GPU queue (the model cannot read a non-image).
            if (verifyMagic && !hasImageSignature(file)) {
                throw ApiException.badRequest(ErrorCode.UNSUPPORTED_IMAGE_TYPE,
                        "The content of " + safeName(file) + " is not a JPEG, PNG or WEBP image");
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
            job = saveUploads(photos);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to store uploads", e);
        }
        // The key is linked before the worker sees the job: the losing request of a same-key race is dropped unrun.
        if (keyed) {
            Job linked = linkIdempotencyKey(idempotencyKey, job);
            if (linked != job) {
                return linked;
            }
        }
        try {
            dispatch(job);
        } catch (TaskRejectedException shuttingDown) {
            if (keyed) {
                idempotencyKeys.deleteById(idempotencyKey); // its job is gone: the re-upload after the restart is new
            }
            throw shuttingDown;
        }
        return job;
    }

    @Override
    public Job getJobOrThrow(String id) {
        return jobRepository.findById(id).orElseThrow(() -> ApiException.jobNotFound(id));
    }

    @Override
    public Job retry(String id) {
        Job job = getJobOrThrow(id);
        Job found = getJobOrThrow(id); // a second, unchanged copy (no persistence context spans the two reads)
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
        try {
            jobWorker.dispatch(id);
        } catch (TaskRejectedException shuttingDown) {
            // Shutdown has begun (the executor refuses work while Tomcat still answers): no worker would run the new
            // attempt, so the job goes back to the FAILED attempt this request found.
            jobRepository.save(found);
            throw shuttingDown;
        }
        jobMetrics.jobRetried();
        log.info("Job retried: {} attempt={}", id, job.getAttempt());
        return job;
    }

    @Override
    public JobPage listJobs(Integer limit, String cursor) {
        int size = limit == null ? DEFAULT_LIMIT : limit;
        if (size < 1 || size > MAX_LIMIT) {
            throw ApiException.badRequest(ErrorCode.INVALID_REQUEST, "limit must be between 1 and " + MAX_LIMIT);
        }
        // One row more than the page tells whether another page follows. The cursor stays the last item's id.
        PageRequest window = PageRequest.of(0, size + 1);
        List<Job> rows;
        if (cursor != null && !cursor.isBlank()) {
            Job after = jobRepository.findById(cursor)
                    .orElseThrow(() -> ApiException.badRequest(ErrorCode.INVALID_REQUEST, "Unknown cursor"));
            rows = jobRepository.findNewestFirstAfter(after.getCreatedAt(), after.getId(), window);
        } else {
            rows = jobRepository.findNewestFirst(window);
        }
        boolean more = rows.size() > size;
        List<Job> items = List.copyOf(more ? rows.subList(0, size) : rows);
        String nextCursor = more ? items.get(items.size() - 1).getId() : null;
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

    /**
     * Insert-only. When a concurrent request linked the key first, answer with that request's job and drop this one:
     * it is stored but not yet dispatched, so no worker ever runs it.
     */
    Job linkIdempotencyKey(String key, Job job) {
        try {
            idempotencyKeys.saveAndFlush(new IdempotencyKeyEntry(key, job.getId(), Instant.now()));
            return job;
        } catch (DataIntegrityViolationException raced) {
            Optional<Job> first = findJobForKey(key);
            if (first.isEmpty()) {
                return job;
            }
            log.info("Idempotency-Key raced: jobId={} answers instead of {}, which is dropped", first.get().getId(), job.getId());
            discard(job);
            return first.get();
        }
    }

    // ------------------------------------------------------------------ shared

    /** v0: store and start at once. */
    private Job store(List<MultipartFile> photos) throws IOException {
        Job job = saveUploads(photos);
        dispatch(job);
        return job;
    }

    /**
     * Copies the photos and saves the job as PENDING; the caller dispatches it. When a copy or the save fails, the
     * photos already copied for this request are deleted again (no job owns them).
     */
    Job saveUploads(List<MultipartFile> photos) throws IOException {
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
        List<Path> written = new ArrayList<>();
        try {
            for (MultipartFile file : photos) {
                String name = safeName(file);
                Path target = uniqueTarget(Path.of(uploadDir), storedName(jobId, name));
                written.add(target);
                try (InputStream in = file.getInputStream()) { // Files.copy leaves its input open
                    Files.copy(in, target);
                }
                uploads.add(new StoredUpload(name, file.getSize(), target.toString()));
            }
            job.setUploads(uploads);
            job.setResultPath(null);
            job.setUploadMs(RequestStartFilter.elapsedMs());
            jobRepository.save(job);
        } catch (IOException | RuntimeException e) {
            deleteFiles(written);
            throw e;
        }
        return job;
    }

    /**
     * Hands the job to the worker. Once shutdown has begun the task executor rejects new work while Tomcat still
     * answers: the job is then removed again with its photos, so no PENDING job is left that no worker will run.
     */
    private void dispatch(Job job) {
        try {
            // Invoke another Spring bean so @Async runs through its proxy.
            jobWorker.dispatch(job.getId());
        } catch (TaskRejectedException shuttingDown) {
            discard(job);
            throw shuttingDown;
        }
        jobMetrics.jobCreated();
        log.info("Job created: {} status={} worker={}", job.getId(), job.getStatus(), jobWorker.type());
    }

    /** A job that never reached a worker: its row and its stored photos go. */
    private void discard(Job job) {
        jobRepository.deleteById(job.getId());
        deleteFiles(job.getUploads().stream().map(upload -> Path.of(upload.path())).toList());
    }

    private void deleteFiles(List<Path> files) {
        for (Path file : files) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                log.warn("Could not delete {}: {}", file, e.toString());
            }
        }
    }

    /** JPEG (FF D8 FF), PNG (89 'PNG' 0D 0A 1A 0A) or WEBP ('RIFF' size 'WEBP'), whatever the declared type says. */
    static boolean hasImageSignature(MultipartFile file) {
        byte[] head;
        try (InputStream in = file.getInputStream()) {
            head = in.readNBytes(12);
        } catch (IOException e) {
            return false;
        }
        return startsWith(head, 0, 0xFF, 0xD8, 0xFF)
                || startsWith(head, 0, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)
                || (startsWith(head, 0, 'R', 'I', 'F', 'F') && startsWith(head, 8, 'W', 'E', 'B', 'P'));
    }

    private static boolean startsWith(byte[] bytes, int offset, int... expected) {
        if (bytes.length < offset + expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if ((bytes[offset + i] & 0xFF) != expected[i]) {
                return false;
            }
        }
        return true;
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

    /**
     * Disk name of an upload: {jobId}_{the client's name}, with characters some file systems refuse (control
     * characters and {@code < > : " / \ | ? *}) replaced by '_' and the name cut to MAX_NAME_BYTES of UTF-8, keeping
     * a short extension. StoredUpload.name keeps the name as sent.
     */
    static String storedName(String jobId, String name) {
        String clean = name.replaceAll("[\\p{Cntrl}<>:\"/\\\\|?*]", "_");
        int dot = clean.lastIndexOf('.');
        String ext = dot > 0 && clean.length() - dot <= 10 ? clean.substring(dot) : "";
        String stem = cutUtf8(clean.substring(0, clean.length() - ext.length()), MAX_NAME_BYTES - utf8Length(ext));
        return jobId + "_" + (stem.isEmpty() ? "upload" : stem) + ext;
    }

    /** The longest prefix of text (whole code points) that is at most maxBytes in UTF-8. */
    private static String cutUtf8(String text, int maxBytes) {
        StringBuilder out = new StringBuilder();
        int bytes = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            int size = utf8Length(new String(Character.toChars(codePoint)));
            if (bytes + size > maxBytes) {
                break;
            }
            out.appendCodePoint(codePoint);
            bytes += size;
            i += Character.charCount(codePoint);
        }
        return out.toString();
    }

    private static int utf8Length(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
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
