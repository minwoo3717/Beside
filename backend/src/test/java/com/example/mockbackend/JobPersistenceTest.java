package com.example.mockbackend;

import com.example.mockbackend.domain.Job;
import com.example.mockbackend.domain.JobRun;
import com.example.mockbackend.domain.JobStatus;
import com.example.mockbackend.domain.RunDetails;
import com.example.mockbackend.domain.StoredUpload;
import com.example.mockbackend.exception.ErrorCode;
import com.example.mockbackend.repository.IdempotencyKeyEntry;
import com.example.mockbackend.repository.IdempotencyKeyRepository;
import com.example.mockbackend.repository.JobRepository;
import com.example.mockbackend.repository.JobRunRepository;
import com.example.mockbackend.service.JobFinisher;
import com.example.mockbackend.service.JobService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.awaitility.Awaitility.await;

/**
 * H2 file database (PLAN stage 0): what a running server knew must still be there after a restart.
 * A restart is simulated by opening two Spring contexts, one after the other, on the same database file.
 * Worker threads do not survive a restart, so jobs left PENDING/PROCESSING must end FAILED at the next startup.
 */
class JobPersistenceTest {
    private static final byte[] SAMPLE_GLB = "glTF persisted sample".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path dir;

    @Test
    void jobsSurviveRestart() throws Exception {
        String jobId;
        try (ConfigurableApplicationContext first = start()) {
            JobService jobs = first.getBean(JobService.class);
            jobId = jobs.submitJobV1(List.of(jpeg("dog.jpg", 3)), "restart-key").getId();
            await().pollInterval(Duration.ofMillis(25)).atMost(Duration.ofSeconds(5))
                    .until(() -> jobs.getJobOrThrow(jobId).getStatus() == JobStatus.COMPLETED);
        }

        try (ConfigurableApplicationContext second = start()) {
            JobService jobs = second.getBean(JobService.class);
            Job reloaded = jobs.getJobOrThrow(jobId);
            assertThat(reloaded.getStatus()).isEqualTo(JobStatus.COMPLETED);
            assertThat(reloaded.getProgress()).isEqualTo(1.0);
            assertThat(reloaded.getAttempt()).isEqualTo(1);
            assertThat(reloaded.getQueuedAt()).isNotNull();
            assertThat(reloaded.getProcessingStartedAt()).isNotNull();
            assertThat(reloaded.getFinishedAt()).isNotNull();
            assertThat(reloaded.getResultPath()).isEqualTo(dir.resolve("sample-dog.glb").toString());
            assertThat(reloaded.getUploads()).hasSize(1);
            assertThat(reloaded.getUploads().get(0).name()).isEqualTo("dog.jpg");
            assertThat(reloaded.getUploads().get(0).bytes()).isEqualTo(3);
            assertThat(Path.of(reloaded.getUploads().get(0).path())).exists();

            // Same Idempotency-Key after the restart: the same job comes back and no new job is created.
            assertThat(jobs.submitJobV1(List.of(jpeg("dog.jpg", 3)), "restart-key").getId()).isEqualTo(jobId);
            assertThat(jobs.listJobs(100, null).items()).extracting(Job::getId).containsExactly(jobId);

            // The run's measurement row (docs/METRICS.md §1.1) is there too; mock runs carry no inference numbers.
            assertThat(second.getBean(JobRunRepository.class).findByJobIdOrderByAttemptAsc(jobId)).singleElement().satisfies(run -> {
                assertThat(run.getId()).isEqualTo(jobId + ":1");
                assertThat(run.getStatus()).isEqualTo(JobStatus.COMPLETED);
                assertThat(run.getWorkerType()).isEqualTo("mock");
                assertThat(run.getUploadBytes()).isEqualTo(3L);
                assertThat(run.getTotalMs()).isNotNull();
                assertThat(run.getInferMs()).isNull();
                assertThat(run.getGlbBytes()).isNull();
                assertThat(run.getPassthrough()).isNull();
            });
        }
    }

    @Test
    void jobRunsAreInsertOnly() throws Exception {
        try (ConfigurableApplicationContext context = start()) {
            JobRunRepository runs = context.getBean(JobRunRepository.class);
            Job job = job("job-a", JobStatus.COMPLETED, Instant.now());
            runs.saveAndFlush(JobRun.of(job, job.getFinishedAt(), "mock", RunDetails.NONE));

            // Measuring the same attempt twice is a programming error; it must not overwrite the first row (insert, not merge).
            job.setStatus(JobStatus.FAILED);
            job.setErrorCode("INFERENCE_FAILED");
            assertThatThrownBy(() -> runs.saveAndFlush(JobRun.of(job, job.getFinishedAt(), "mock", RunDetails.NONE)))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(runs.findById("job-a:1")).map(JobRun::getStatus).contains(JobStatus.COMPLETED);
        }
    }

    @Test
    void jobRunsSurviveRestart() throws Exception {
        Instant finishedAt = Instant.now();
        RunDetails details = new RunDetails(48210L, 9120L, 312_000_000L, 10342L, 19876L, 850L, 5_242_880L, false,
                "animallift-baseline@abc1234");
        try (ConfigurableApplicationContext first = start()) {
            Job job = job("job-b", JobStatus.COMPLETED, finishedAt);
            first.getBean(JobRunRepository.class).saveAndFlush(JobRun.of(job, finishedAt, "real", details));
        }

        try (ConfigurableApplicationContext second = start()) {
            JobRun run = second.getBean(JobRunRepository.class).findById("job-b:1").orElseThrow();
            assertThat(run.getJobId()).isEqualTo("job-b");
            assertThat(run.getAttempt()).isEqualTo(1);
            assertThat(run.getWorkerType()).isEqualTo("real");
            assertThat(run.getStatus()).isEqualTo(JobStatus.COMPLETED);
            assertThat(run.getErrorCode()).isNull();
            assertThat(run.getFinishedAt()).isCloseTo(finishedAt, within(1, ChronoUnit.MILLIS));
            assertThat(run.getUploadBytes()).isEqualTo(1L);
            assertThat(run.getQueuedMs()).isNotNull();
            assertThat(run.getProcessingMs()).isNotNull();
            assertThat(run.getTotalMs()).isNotNull();
            assertThat(run.getInferMs()).isEqualTo(48210L);
            assertThat(run.getGpuPeakMB()).isEqualTo(9120L);
            assertThat(run.getModelParams()).isEqualTo(312_000_000L);
            assertThat(run.getOutputVertices()).isEqualTo(10342L);
            assertThat(run.getOutputTriangles()).isEqualTo(19876L);
            assertThat(run.getConvertMs()).isEqualTo(850L);
            assertThat(run.getGlbBytes()).isEqualTo(5_242_880L);
            assertThat(run.getPassthrough()).isFalse();
            assertThat(run.getModelVersion()).isEqualTo("animallift-baseline@abc1234");
            assertThat(run.result()).isEqualTo("COMPLETED");
        }
    }

    @Test
    void finishingTheSameAttemptTwiceKeepsTheFirstRowAndDoesNotThrow() throws Exception {
        try (ConfigurableApplicationContext context = start()) {
            JobFinisher finisher = context.getBean(JobFinisher.class);
            JobRunRepository runs = context.getBean(JobRunRepository.class);
            Job job = job("job-c", JobStatus.PROCESSING, null);
            context.getBean(JobRepository.class).save(job);

            finisher.complete(job, dir.resolve("sample-dog.glb").toString(), Instant.now(), "mock");
            // A second terminal transition of one attempt is a bug; it must break neither the worker nor the first measurement.
            assertThatCode(() -> finisher.fail(job, ErrorCode.INFERENCE_FAILED, "finished twice", "mock"))
                    .doesNotThrowAnyException();

            assertThat(runs.findByJobIdOrderByAttemptAsc("job-c")).singleElement().satisfies(run -> {
                assertThat(run.getStatus()).isEqualTo(JobStatus.COMPLETED);
                assertThat(run.result()).isEqualTo("COMPLETED");
            });
        }
    }

    @Test
    void idempotencyKeyKeepsItsFirstJob() throws Exception {
        try (ConfigurableApplicationContext context = start()) {
            IdempotencyKeyRepository keys = context.getBean(IdempotencyKeyRepository.class);
            keys.saveAndFlush(new IdempotencyKeyEntry("same-key", "job-a", Instant.now()));

            // A racing second request must not overwrite the key (insert-only, not merge).
            assertThatThrownBy(() -> keys.saveAndFlush(new IdempotencyKeyEntry("same-key", "job-b", Instant.now())))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(keys.findById("same-key")).map(IdempotencyKeyEntry::getJobId).contains("job-a");
        }
    }

    @Test
    void jobsInFlightAtShutdownAreFailedOnStartup() throws Exception {
        String jobId = UUID.randomUUID().toString();
        try (ConfigurableApplicationContext first = start()) {
            // A crash mid-processing: the row says PROCESSING but no worker thread survives a restart.
            Instant now = Instant.now();
            Job job = new Job();
            job.setId(jobId);
            job.setStatus(JobStatus.PROCESSING);
            job.setCreatedAt(now);
            job.setUpdatedAt(now);
            job.setQueuedAt(now);
            job.setProcessingStartedAt(now);
            job.setProgress(0.5);
            job.setAttempt(1);
            job.setUploads(List.of(new StoredUpload("crash.jpg", 1, dir.resolve("crash.jpg").toString())));
            first.getBean(JobRepository.class).save(job);
        }

        try (ConfigurableApplicationContext second = start()) {
            JobService jobs = second.getBean(JobService.class);
            Job recovered = jobs.getJobOrThrow(jobId);
            assertThat(recovered.getStatus()).as("clients must not poll forever").isEqualTo(JobStatus.FAILED);
            assertThat(recovered.getErrorCode()).isEqualTo("INTERNAL_ERROR");
            assertThat(recovered.getErrorMessage()).contains("restart");
            assertThat(recovered.getProgress()).isNull();
            assertThat(recovered.getFinishedAt()).isNotNull();

            // The client can retry: the worker runs the stored uploads again.
            jobs.retry(jobId);
            await().pollInterval(Duration.ofMillis(25)).atMost(Duration.ofSeconds(5))
                    .until(() -> jobs.getJobOrThrow(jobId).getStatus() == JobStatus.COMPLETED);
            assertThat(jobs.getJobOrThrow(jobId).getAttempt()).isEqualTo(2);

            // One job_runs row per attempt: the run cut by the restart and the retried one. The row follows the status save.
            JobRunRepository runs = second.getBean(JobRunRepository.class);
            await().pollInterval(Duration.ofMillis(25)).atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(runs.findByJobIdOrderByAttemptAsc(jobId)).hasSize(2));
            List<JobRun> attempts = runs.findByJobIdOrderByAttemptAsc(jobId);
            assertThat(attempts).extracting(JobRun::getId).containsExactly(jobId + ":1", jobId + ":2");
            assertThat(attempts).extracting(JobRun::result).containsExactly("FAILED:INTERNAL_ERROR", "COMPLETED");
            assertThat(attempts.get(0).getErrorCode()).isEqualTo("INTERNAL_ERROR");
            assertThat(attempts.get(0).getWorkerType()).isEqualTo("mock");
            assertThat(attempts.get(0).getInferMs()).isNull();
            assertThat(attempts.get(0).getPassthrough()).isNull();
        }

        // Both runs are measured: the one cut by the restart and the retried one (docs/METRICS.md §1).
        List<JsonNode> events = new ArrayList<>();
        for (String line : Files.readAllLines(dir.resolve("events.jsonl"), StandardCharsets.UTF_8)) {
            JsonNode event = new ObjectMapper().readTree(line);
            if (event.path("jobId").asText().equals(jobId)) {
                events.add(event);
            }
        }
        assertThat(events).extracting(e -> e.path("result").asText()).containsExactly("FAILED:INTERNAL_ERROR", "COMPLETED");
        assertThat(events).extracting(e -> e.path("attempt").asInt()).containsExactly(1, 2);
    }

    private ConfigurableApplicationContext start() throws IOException {
        Path sample = dir.resolve("sample-dog.glb");
        if (!Files.exists(sample)) {
            Files.write(sample, SAMPLE_GLB);
        }
        String database = dir.resolve("db").resolve("beside").toAbsolutePath().toString().replace('\\', '/');
        return new SpringApplicationBuilder(MockBackendApplication.class)
                .web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF)
                .registerShutdownHook(false)
                .run("--spring.datasource.url=jdbc:h2:file:" + database + ";DB_CLOSE_ON_EXIT=FALSE",
                        "--storage.upload.dir=" + dir.resolve("uploads"),
                        "--storage.result.sample=" + sample,
                        "--storage.events.path=" + dir.resolve("events.jsonl"),
                        "--mock.worker.pending-ms=50",
                        "--mock.worker.processing-ms=50");
    }

    private static MockMultipartFile jpeg(String name, int bytes) {
        return new MockMultipartFile("photos", name, "image/jpeg", new byte[bytes]);
    }

    /** A job row as a worker leaves it, without the HTTP layer. */
    private Job job(String id, JobStatus status, Instant finishedAt) {
        Instant now = Instant.now();
        Job job = new Job();
        job.setId(id);
        job.setStatus(status);
        job.setCreatedAt(now.minusSeconds(5));
        job.setUpdatedAt(now);
        job.setQueuedAt(now.minusSeconds(5));
        job.setProcessingStartedAt(now.minusSeconds(4));
        job.setFinishedAt(finishedAt);
        job.setAttempt(1);
        job.setUploads(List.of(new StoredUpload("dog.jpg", 1, dir.resolve("dog.jpg").toString())));
        return job;
    }
}
