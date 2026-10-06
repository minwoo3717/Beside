package com.example.mockbackend.service;

import com.example.mockbackend.domain.Job;
import com.example.mockbackend.domain.JobStatus;
import com.example.mockbackend.exception.ErrorCode;
import com.example.mockbackend.repository.JobRepository;
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
 *   <li>Waits for an inference slot (inference.max-concurrency, 1 = one GPU). The job stays PENDING meanwhile, so
 *       queuedMs measures the queue and inference.timeout-ms only counts the job's own inference.</li>
 *   <li>PROCESSING with progress null (the service reports none), then POST /infer through {@link InferenceClient}.</li>
 *   <li>Checks that the answer is a glTF 2.0 binary and copies it to {storage.result.dir}/{jobId}/base.glb, which the
 *       asset endpoint serves. The run ends through JobFinisher (status, then the job_runs row and the events.jsonl
 *       line, carrying the /infer metrics, the passthrough flag, the model version and the GLB size).</li>
 * </ol>
 * Failures end the run FAILED with their job failure code (InferenceClient documents the mapping). An interrupted
 * worker (shutdown) or an unexpected error ends it with INTERNAL_ERROR, so the app never polls a run that cannot end.
 */
@Service
@Profile("real")
public class RealJobWorker implements JobWorker {
    private static final byte[] GLB_MAGIC = {'g', 'l', 'T', 'F'};

    private final JobRepository jobRepository;
    private final JobFinisher jobFinisher;
    private final InferenceClient inferenceClient;
    private final Path resultDir;
    private final Semaphore inferenceSlots;
    private final Logger log = LoggerFactory.getLogger(RealJobWorker.class);

    public RealJobWorker(JobRepository jobRepository, JobFinisher jobFinisher, InferenceClient inferenceClient,
                         @Value("${storage.result.dir:storage/results}") String resultDir,
                         @Value("${inference.max-concurrency:1}") int maxConcurrency) {
        this.jobRepository = jobRepository;
        this.jobFinisher = jobFinisher;
        this.inferenceClient = inferenceClient;
        this.resultDir = Path.of(resultDir);
        this.inferenceSlots = new Semaphore(Math.max(1, maxConcurrency), true);
    }

    @Override
    public String type() {
        return "real";
    }

    @Async
    @Override
    public void dispatch(String jobId) {
        Instant start = Instant.now();
        try {
            inferenceSlots.acquire();
            try {
                run(jobId, start);
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
        }
    }

    private void run(String jobId, Instant start) throws InterruptedException {
        Job job = jobRepository.findById(jobId).orElse(null);
        if (job == null) {
            log.warn("jobId={} not found for real worker", jobId);
            return;
        }
        Instant processingStarted = Instant.now();
        job.setProcessingStartedAt(processingStarted);
        job.setProgress(null); // the inference service reports no progress (openapi: null when unknown)
        job.setUpdatedAt(processingStarted);
        job.setStatus(JobStatus.PROCESSING);
        jobRepository.save(job);
        log.info("jobId={} stage=PROCESSING POST {}", jobId, inferenceClient.inferUri());

        try {
            InferenceClient.Result result = inferenceClient.infer(jobId, imagePaths(job));
            StoredGlb stored = storeBaseGlb(jobId, result.glbPath());
            jobFinisher.complete(job, stored.path().toString(), start, type(), result.details(stored.bytes()));
            if (result.passthrough()) {
                log.warn("jobId={} stage=COMPLETED with the passthrough sample GLB: not an inference result, "
                        + "leave it out of measurements (job_runs.passthrough = true)", jobId);
            } else {
                log.info("jobId={} stage=COMPLETED durationMs={} modelVersion={} metrics={}", jobId, job.getDurationMs(),
                        result.modelVersion(), result.metrics());
            }
        } catch (InferenceFailure failure) {
            jobFinisher.fail(job, failure.code(), failure.getMessage(), type());
            log.warn("jobId={} stage=FAILED code={} {} [{}]", jobId, failure.code(), failure.getMessage(), failure.logDetail());
        } catch (RuntimeException unexpected) {
            log.error("jobId={} real worker error", jobId, unexpected);
            // The exception text may name server paths; it stays in the log.
            jobFinisher.fail(job, ErrorCode.INTERNAL_ERROR, "Real worker error (" + unexpected.getClass().getSimpleName() + ")", type());
        }
    }

    private static List<Path> imagePaths(Job job) {
        return job.getUploads().stream().map(upload -> Path.of(upload.path())).toList();
    }

    /** The stored base GLB and its size (job_runs.glbBytes, the same number as asset.bytes). */
    private record StoredGlb(Path path, long bytes) {
    }

    /** GLB_SPEC section 6: the asset endpoint serves {storage.result.dir}/{jobId}/base.glb. */
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
            Path target = resultDir.resolve(jobId).resolve("base.glb");
            Files.createDirectories(target.getParent());
            // The service may already have written straight into the result directory (shared volume).
            if (!(Files.exists(target) && Files.isSameFile(glb, target))) {
                Files.copy(glb, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return new StoredGlb(target, Files.size(target));
        } catch (IOException e) {
            throw new InferenceFailure(ErrorCode.CONVERSION_FAILED,
                    "Could not store the GLB (" + e.getClass().getSimpleName() + ")", "glbPath=" + glb + ": " + e);
        }
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
}
