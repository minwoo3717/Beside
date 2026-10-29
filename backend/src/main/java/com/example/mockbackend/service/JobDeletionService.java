package com.example.mockbackend.service;

import com.example.mockbackend.domain.Job;
import com.example.mockbackend.domain.JobStatus;
import com.example.mockbackend.domain.StoredUpload;
import com.example.mockbackend.exception.ApiException;
import com.example.mockbackend.exception.ErrorCode;
import com.example.mockbackend.repository.IdempotencyKeyRepository;
import com.example.mockbackend.repository.JobRepository;
import com.example.mockbackend.repository.PetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Deleting finished jobs (DELETE /api/v1/jobs/{id}, DELETE /api/v1/pets/{id}?withJobs=true). Removes what belongs to the
 * job: share links are revoked, Idempotency-Keys pointing at it are released, a pet that chose it as cover forgets it,
 * the jobs row goes, then the files (uploaded photos, {storage.result.dir}/{jobId}, {storage.tmp.dir}/{jobId}, the
 * thumbnail). job_runs rows and events.jsonl lines stay: they are measurements of runs that happened.
 * The mock's shared sample GLB is never touched (it is not inside the job's result folder).
 * PENDING and PROCESSING jobs are refused (409 JOB_IN_PROGRESS): a worker still holds them. A file that cannot be
 * deleted (locked on Windows, already gone) is logged; the job is deleted anyway, the leftovers are disk only.
 * Separate from JobServiceImpl because ShareService already depends on JobService.
 */
@Service
public class JobDeletionService {
    private final JobRepository jobRepository;
    private final IdempotencyKeyRepository idempotencyKeys;
    private final PetRepository petRepository;
    private final ShareService shareService;
    private final ThumbnailService thumbnails;
    private final Path resultDir;
    private final Path tmpDir;
    private final Logger log = LoggerFactory.getLogger(JobDeletionService.class);

    public JobDeletionService(JobRepository jobRepository, IdempotencyKeyRepository idempotencyKeys,
                              PetRepository petRepository, ShareService shareService, ThumbnailService thumbnails,
                              @Value("${storage.result.dir:storage/results}") String resultDir,
                              @Value("${storage.tmp.dir:storage/tmp}") String tmpDir) {
        this.jobRepository = jobRepository;
        this.idempotencyKeys = idempotencyKeys;
        this.petRepository = petRepository;
        this.shareService = shareService;
        this.thumbnails = thumbnails;
        this.resultDir = Path.of(resultDir);
        this.tmpDir = Path.of(tmpDir);
    }

    /** @throws ApiException 404 JOB_NOT_FOUND, 409 JOB_IN_PROGRESS */
    public void delete(String jobId) {
        Job job = jobRepository.findById(jobId).orElseThrow(() -> ApiException.jobNotFound(jobId));
        requireFinished(job);
        remove(job);
    }

    /** All or nothing: refuses before deleting anything when one of them is still running. @throws ApiException 409 */
    public void deleteAll(List<Job> jobs) {
        jobs.forEach(JobDeletionService::requireFinished);
        jobs.forEach(this::remove);
    }

    static void requireFinished(Job job) {
        if (job.getStatus() == JobStatus.PENDING || job.getStatus() == JobStatus.PROCESSING) {
            throw ApiException.conflict(ErrorCode.JOB_IN_PROGRESS, job.getId(), "Job is " + job.getStatus()
                    + "; cancel a PENDING job or wait for a PROCESSING one to end before deleting it");
        }
    }

    private void remove(Job job) {
        String jobId = job.getId();
        int links = shareService.revokeAllForJob(jobId);
        int keys = idempotencyKeys.deleteByJobId(jobId);
        petRepository.clearCover(jobId);
        jobRepository.deleteById(jobId);
        int failedFiles = deleteFiles(job);
        log.info("Job deleted: {} (shares revoked={}, idempotency keys={}, files not deleted={}); job_runs kept",
                jobId, links, keys, failedFiles);
    }

    /** Returns how many files or folders could not be deleted. */
    private int deleteFiles(Job job) {
        String jobId = job.getId();
        int failed = 0;
        for (StoredUpload upload : job.getUploads()) {
            if (upload.path() == null) {
                continue;
            }
            Path photo = Path.of(upload.path());
            // Stored as {storage.upload.dir}/{jobId}_{name}: never follow a path that is not this job's own upload.
            if (photo.getFileName() == null || !photo.getFileName().toString().startsWith(jobId + "_")) {
                log.warn("jobId={} upload path left alone (not this job's file): {}", jobId, photo);
                continue;
            }
            failed += deleteQuietly(jobId, photo) ? 0 : 1;
        }
        failed += deleteFolder(jobId, resultDir.resolve(jobId)) ? 0 : 1;
        failed += deleteFolder(jobId, tmpDir.resolve(jobId)) ? 0 : 1;
        thumbnails.delete(jobId);
        return failed;
    }

    private boolean deleteQuietly(String jobId, Path file) {
        try {
            Files.deleteIfExists(file);
            return true;
        } catch (IOException | RuntimeException e) {
            log.warn("jobId={} could not delete {}: {}", jobId, file, e.toString());
            return false;
        }
    }

    private boolean deleteFolder(String jobId, Path folder) {
        if (!Files.isDirectory(folder)) {
            return deleteQuietly(jobId, folder);
        }
        try (Stream<Path> walk = Files.walk(folder)) {
            return walk.sorted(Comparator.reverseOrder()).map(path -> deleteQuietly(jobId, path)).reduce(true, Boolean::logicalAnd);
        } catch (IOException | RuntimeException e) {
            log.warn("jobId={} could not delete folder {}: {}", jobId, folder, e.toString());
            return false;
        }
    }
}
