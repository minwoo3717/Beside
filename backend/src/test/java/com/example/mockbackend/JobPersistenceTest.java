package com.example.mockbackend;

import com.example.mockbackend.domain.Job;
import com.example.mockbackend.domain.JobStatus;
import com.example.mockbackend.domain.StoredUpload;
import com.example.mockbackend.repository.IdempotencyKeyEntry;
import com.example.mockbackend.repository.IdempotencyKeyRepository;
import com.example.mockbackend.repository.JobRepository;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
}
