package com.example.mockbackend.service;

import com.example.mockbackend.domain.Job;
import com.example.mockbackend.domain.JobStatus;
import com.example.mockbackend.domain.StoredUpload;
import com.example.mockbackend.exception.ErrorCode;
import com.example.mockbackend.repository.JobRepository;
import com.example.mockbackend.domain.RunDetails;
import com.example.mockbackend.service.preprocess.CutoutStage;
import com.example.mockbackend.service.preprocess.PreparedPhoto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Semaphore;

/**
 * Profile "real": the 3D model comes from the Python inference service (generation/inference_service).
 * <ol>
 *   <li>Preprocesses the first photo (CutoutStage: crop, background removal, square PNG) while still PENDING and
 *       before the slot, so the CPU work overlaps the GPU run of the job ahead; a failed cutout sends the original.</li>
 *   <li>Waits for an inference slot (inference.max-concurrency, 1 = one GPU). The job stays PENDING meanwhile, so
 *       queuedMs measures the queue and inference.timeout-ms only counts the job's own inference.</li>
 *   <li>With inference.offline-wait-ms &gt; 0 (prod): while {@link InferenceHealthMonitor} does not read UP (the GPU PC
 *       is off or its model is still loading) the job keeps waiting as PENDING, checked every inference.offline-poll-ms,
 *       and fails INFERENCE_UNAVAILABLE only after that long, counted from dispatch (the wait for the slot is part
 *       of it, so N queued jobs do not wait N times as long). 0 (default) keeps the old behaviour: no wait, a missing
 *       service fails the run at once.</li>
 *   <li>PROCESSING with progress null (the service reports none), then POST /infer (inference.transfer=path) or
 *       POST /infer/upload + GET glbUrl (upload) through {@link InferenceClient}. With the wait above on, a request
 *       still in flight when /healthz names another service process is given up (failure drill FD16b, see
 *       {@link #restartWatch}).</li>
 *   <li>Checks that the answer is a glTF 2.0 binary and copies it to {storage.result.dir}/{jobId}/base.glb, which the
 *       asset endpoint serves (a copy that fails is this server's disk: INTERNAL_ERROR). The run ends through
 *       JobFinisher (status, then the job_runs row and the events.jsonl line, carrying the /infer metrics, the
 *       passthrough flag, the model version and the GLB size).</li>
 *   <li>Upload mode, success or failure: InferenceClient.cleanup deletes the temporary GLB and sends
 *       DELETE /artifacts/{jobId}; a failed cleanup is only logged.</li>
 * </ol>
 * Failures end the run FAILED with their job failure code (InferenceClient documents the mapping). An interrupted
 * worker (shutdown) or an unexpected error ends it with INTERNAL_ERROR, so the app never polls a run that cannot end;
 * that includes an error before PROCESSING (reading the job, the cutout, the inference-service check, the PROCESSING
 * save), which used to leave the job PENDING until the next restart.
 */
@Service
@Profile("real")
public class RealJobWorker implements JobWorker {
    private static final byte[] GLB_MAGIC = {'g', 'l', 'T', 'F'};
    /** What the run starts from when inference.offline-wait-ms is 0: no reading, so no instance to watch. */
    private static final InferenceHealthMonitor.Status NOT_CHECKED = new InferenceHealthMonitor.Status(
            InferenceHealthMonitor.State.UNKNOWN, null, null, Instant.EPOCH, "not checked (offline wait off)");

    private final JobRepository jobRepository;
    private final JobFinisher jobFinisher;
    private final InferenceClient inferenceClient;
    private final Path resultDir;
    private final Semaphore inferenceSlots;
    private final InferenceHealthMonitor healthMonitor;
    private final CutoutStage cutoutStage;
    private final long offlineWaitMs;
    private final long offlinePollMs;
    private final Logger log = LoggerFactory.getLogger(RealJobWorker.class);

    public RealJobWorker(JobRepository jobRepository, JobFinisher jobFinisher, InferenceClient inferenceClient,
                         InferenceHealthMonitor healthMonitor, CutoutStage cutoutStage,
                         @Value("${storage.result.dir:storage/results}") String resultDir,
                         @Value("${inference.max-concurrency:1}") int maxConcurrency,
                         @Value("${inference.offline-wait-ms:0}") long offlineWaitMs,
                         @Value("${inference.offline-poll-ms:5000}") long offlinePollMs) {
        this.jobRepository = jobRepository;
        this.jobFinisher = jobFinisher;
        this.inferenceClient = inferenceClient;
        this.healthMonitor = healthMonitor;
        this.cutoutStage = cutoutStage;
        this.resultDir = Path.of(resultDir);
        this.inferenceSlots = new Semaphore(Math.max(1, maxConcurrency), true);
        this.offlineWaitMs = Math.max(0, offlineWaitMs);
        this.offlinePollMs = Math.max(10, offlinePollMs);
    }

    @Override
    public String type() {
        return "real";
    }

    @Async
    @Override
    public void dispatch(String jobId) {
        Instant start = Instant.now();
        // The offline wait counts from here, not from taking the slot: a job queued behind others that wait for the
        // same stopped GPU must not start its own full wait only when they give up (N jobs would wait N x the limit).
        long offlineDeadline = System.nanoTime() + offlineWaitMs * 1_000_000;
        PreparedPhoto photo = null;
        try {
            photo = prepare(jobId);
            inferenceSlots.acquire();
            try {
                run(jobId, start, photo, offlineDeadline);
            } finally {
                inferenceSlots.release();
            }
        } catch (InterruptedException e) {
            // Record the failure before restoring the interrupt flag: JDBC calls can fail while it is set.
            try {
                markInterrupted(jobId);
            } catch (RuntimeException databaseClosing) {
                log.warn("jobId={} interrupted during shutdown; InterruptedJobRecovery fails it at the next startup", jobId);
            } finally {
                Thread.currentThread().interrupt();
            }
        } catch (RuntimeException unexpected) {
            // Outside run()'s own handling: reading the job, the cutout, the inference-service check, the PROCESSING
            // save, or a failure write that threw. Escaping the @Async method would leave the job PENDING (or
            // PROCESSING) with no thread left to end it until the next restart. The slot is already released.
            failUnended(jobId, unexpected, photo);
        }
    }

    /** The cutout of the job's first photo; OFF when the job is gone (run() then logs it). */
    private PreparedPhoto prepare(String jobId) throws InterruptedException {
        Job job = jobRepository.findById(jobId).orElse(null);
        return job == null ? PreparedPhoto.off() : cutoutStage.prepare(job);
    }

    private void run(String jobId, Instant start, PreparedPhoto photo, long offlineDeadline) throws InterruptedException {
        Job job = jobRepository.findById(jobId).orElse(null);
        if (job == null) {
            log.warn("jobId={} not found for real worker", jobId);
            return;
        }
        InferenceHealthMonitor.Status ready = awaitInferenceService(jobId, offlineDeadline);
        if (ready == null) {
            InferenceHealthMonitor.Status status = healthMonitor.current();
            jobFinisher.fail(job, ErrorCode.INFERENCE_UNAVAILABLE, "The inference service was not ready within "
                    + offlineWaitMs / 1000 + " s (last check: " + status.state() + ")", type(),
                    photo.apply(RunDetails.NONE));
            log.warn("jobId={} stage=FAILED code=INFERENCE_UNAVAILABLE: inference service not UP within {} ms of dispatch ({})",
                    jobId, offlineWaitMs, status.detail());
            return;
        }
        Instant processingStarted = Instant.now();
        job.setProcessingStartedAt(processingStarted);
        job.setProgress(null); // the inference service reports no progress (openapi: null when unknown)
        job.setUpdatedAt(processingStarted);
        job.setStatus(JobStatus.PROCESSING);
        jobRepository.save(job);
        log.info("jobId={} stage=PROCESSING POST {}", jobId, inferenceClient.requestUri());

        try {
            InferenceClient.Result result = inferenceClient.infer(jobId, photo.paths(imagePaths(job)),
                    photo.names(filenames(job)), restartWatch(ready));
            StoredGlb stored = storeBaseGlb(jobId, result.glbPath());
            jobFinisher.complete(job, stored.path().toString(), start, type(),
                    photo.apply(result.details(stored.bytes())));
            if (result.passthrough()) {
                log.warn("jobId={} stage=COMPLETED with the passthrough sample GLB: not an inference result, "
                        + "leave it out of measurements (job_runs.passthrough = true)", jobId);
            } else {
                log.info("jobId={} stage=COMPLETED durationMs={} modelVersion={} metrics={}", jobId, job.getDurationMs(),
                        result.modelVersion(), result.metrics());
            }
        } catch (InferenceFailure failure) {
            jobFinisher.fail(job, failure.code(), failure.getMessage(), type(), photo.apply(RunDetails.NONE));
            log.warn("jobId={} stage=FAILED code={} {} [{}]", jobId, failure.code(), failure.getMessage(), failure.logDetail());
        } catch (RuntimeException unexpected) {
            log.error("jobId={} real worker error", jobId, unexpected);
            jobFinisher.fail(job, ErrorCode.INTERNAL_ERROR, unexpectedMessage(unexpected), type(),
                    photo.apply(RunDetails.NONE));
        } finally {
            // Upload mode: the downloaded temporary GLB and the service's job folder. After the run has ended and
            // never throwing, so a failed cleanup cannot change the result. Path mode: nothing to do.
            inferenceClient.cleanup(jobId);
        }
    }

    /**
     * The fresh UP reading the run starts from, at once or before {@code deadline} (System.nanoTime(): dispatch plus
     * inference.offline-wait-ms, so time spent queued for the slot counts); the job stays PENDING while this waits.
     * Null when the service was not UP by then. A job whose deadline passed while it queued gets one fresh reading.
     * When the wait is off (0) there is no reading: {@link #NOT_CHECKED}, and the run finds out from /infer itself.
     */
    private InferenceHealthMonitor.Status awaitInferenceService(String jobId, long deadline) throws InterruptedException {
        if (offlineWaitMs == 0) {
            return NOT_CHECKED;
        }
        // Always a fresh reading (one /healthz call per job): the scheduled one can be up to 15 s old.
        boolean logged = false;
        while (true) {
            InferenceHealthMonitor.Status status = healthMonitor.refresh();
            if (status.ready()) {
                if (logged) {
                    log.info("jobId={} inference service is UP again, starting", jobId);
                }
                return status;
            }
            long leftMs = (deadline - System.nanoTime()) / 1_000_000;
            if (leftMs <= 0) {
                return null;
            }
            if (!logged) {
                log.info("jobId={} waits as PENDING: inference service {} ({}), up to {} s", jobId, status.state(),
                        status.detail(), leftMs / 1000);
                logged = true;
            }
            Thread.sleep(Math.min(offlinePollMs, leftMs));
        }
    }

    /**
     * FD16b: once a /healthz reading (the monitor's, every inference.health-poll-ms) names another service process
     * than the one that was UP when the run started, the request in flight went to a process that is gone (a stopped
     * and restarted Pod, a killed service brought back by R7) and will never be answered: InferenceClient cancels it
     * and the slot goes to the next job, instead of waiting out inference.timeout-ms. Nothing without a fresh UP
     * reading that names its process (wait off, an older service): then only the timeouts apply. A reading with no
     * instance (DOWN) changes nothing: a path cut for a while (FD10) keeps the same process and the request survives.
     */
    private InferenceClient.Watch restartWatch(InferenceHealthMonitor.Status ready) {
        String started = ready.instanceId();
        if (started == null) {
            return InferenceClient.Watch.NONE;
        }
        return () -> {
            String now = healthMonitor.current().instanceId();
            return now != null && !now.equals(started)
                    ? "inference service restarted (instance " + started + " -> " + now + ")" : null;
        };
    }

    private static List<Path> imagePaths(Job job) {
        return job.getUploads().stream().map(upload -> Path.of(upload.path())).toList();
    }

    /** The photos' original names, sent with the files in upload mode. */
    private static List<String> filenames(Job job) {
        return job.getUploads().stream().map(StoredUpload::name).toList();
    }

    /** The stored base GLB and its size (job_runs.glbBytes, the same number as asset.bytes). */
    private record StoredGlb(Path path, long bytes) {
    }

    /**
     * GLB_SPEC section 6: the asset endpoint serves {storage.result.dir}/{jobId}/base.glb. A GLB that is missing or
     * not glTF 2.0 is the service's failure (CONVERSION_FAILED); one this server cannot copy into its result directory
     * is its own (INTERNAL_ERROR: the disk is full or not writable, failure drill FD4 — it used to say CONVERSION_FAILED,
     * which sent people looking at the converter).
     */
    private StoredGlb storeBaseGlb(String jobId, Path glb) throws InferenceFailure {
        try {
            if (!Files.isRegularFile(glb)) {
                throw new InferenceFailure(ErrorCode.CONVERSION_FAILED,
                        "The GLB from the inference service is missing or unreadable", "glbPath=" + glb);
            }
            if (!isGlb(glb)) {
                throw new InferenceFailure(ErrorCode.CONVERSION_FAILED,
                        "The GLB from the inference service is not a glTF 2.0 binary", "glbPath=" + glb);
            }
        } catch (IOException e) {
            throw new InferenceFailure(ErrorCode.CONVERSION_FAILED,
                    "Could not read the GLB from the inference service (" + e.getClass().getSimpleName() + ")",
                    "glbPath=" + glb + ": " + e);
        }
        try {
            Path target = resultDir.resolve(jobId).resolve("base.glb");
            Files.createDirectories(target.getParent());
            // The service may already have written straight into the result directory (shared volume).
            if (!(Files.exists(target) && Files.isSameFile(glb, target))) {
                Files.copy(glb, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return new StoredGlb(target, Files.size(target));
        } catch (IOException e) {
            if (copyFailureCode(inferenceClient.transfer(), glb) == ErrorCode.CONVERSION_FAILED) {
                throw new InferenceFailure(ErrorCode.CONVERSION_FAILED,
                        "The GLB from the inference service was gone before it was stored (" + e.getClass().getSimpleName() + ")",
                        "glbPath=" + glb + ": " + e);
            }
            throw new InferenceFailure(ErrorCode.INTERNAL_ERROR,
                    "Could not store the GLB on this server (" + e.getClass().getSimpleName() + ")",
                    "glbPath=" + glb + " -> " + resultDir + ": " + e);
        }
    }

    /**
     * Whose failure a GLB copy that failed is. Only in path mode, and only when the service's file is gone afterwards
     * (it vanished from the shared volume between the header check and the copy, e.g. the service's work sweep), is it
     * the service's (CONVERSION_FAILED). Everything else is this server's (INTERNAL_ERROR): the exception cannot tell,
     * since a copy that runs out of disk half way reports both files (FileSystemException(source, target, "No space
     * left on device")), and in upload mode the source is this server's own temporary file.
     */
    static ErrorCode copyFailureCode(InferenceClient.Transfer transfer, Path source) {
        return transfer == InferenceClient.Transfer.PATH && !Files.exists(source)
                ? ErrorCode.CONVERSION_FAILED : ErrorCode.INTERNAL_ERROR;
    }

    /** glTF 2.0 binary header: magic "glTF", then version 2 as a little-endian uint32. Full validation is the converter's job. */
    private static boolean isGlb(Path file) throws IOException {
        byte[] header;
        try (InputStream in = Files.newInputStream(file)) {
            header = in.readNBytes(8);
        }
        return header.length == 8
                && Arrays.equals(Arrays.copyOf(header, 4), GLB_MAGIC)
                && ByteBuffer.wrap(header, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() == 2;
    }

    private void markInterrupted(String jobId) {
        Job job = jobRepository.findById(jobId).orElse(null);
        if (job == null) {
            return;
        }
        jobFinisher.fail(job, ErrorCode.INTERNAL_ERROR, "Real worker interrupted (server shutting down)", type());
        log.warn("jobId={} stage=FAILED (real worker interrupted)", jobId);
    }

    /** error.message of an unexpected error: the class name only, the exception text may name server paths (log only). */
    private static String unexpectedMessage(RuntimeException unexpected) {
        return "Real worker error (" + unexpected.getClass().getSimpleName() + ")";
    }

    /**
     * Ends the run INTERNAL_ERROR after an error that left {@link #dispatch}, reading the job again: the copy the run
     * held may say PROCESSING or FAILED in memory only, when its save is what failed. A run that already ended
     * (COMPLETED or FAILED in the database) stays as it is. One attempt: when the failure cannot be written either
     * (database down) this only logs, and InterruptedJobRecovery fails the job at the next startup.
     */
    private void failUnended(String jobId, RuntimeException unexpected, PreparedPhoto photo) {
        log.error("jobId={} real worker error", jobId, unexpected);
        try {
            Job job = jobRepository.findById(jobId).orElse(null);
            if (job == null) {
                log.warn("jobId={} not found for real worker", jobId);
                return;
            }
            if (job.getStatus() != JobStatus.PENDING && job.getStatus() != JobStatus.PROCESSING) {
                log.warn("jobId={} already {}; left as it is", jobId, job.getStatus());
                return;
            }
            jobFinisher.fail(job, ErrorCode.INTERNAL_ERROR, unexpectedMessage(unexpected), type(),
                    photo != null ? photo.apply(RunDetails.NONE) : RunDetails.NONE);
            log.warn("jobId={} stage=FAILED code=INTERNAL_ERROR ({})", jobId, unexpected.getClass().getSimpleName());
        } catch (RuntimeException notRecorded) {
            log.error("jobId={} could not record the failure ({}); InterruptedJobRecovery fails it at the next startup",
                    jobId, notRecorded.toString());
        }
    }
}
