package com.example.mockbackend;

import com.example.mockbackend.domain.Job;
import com.example.mockbackend.domain.JobStatus;
import com.example.mockbackend.repository.IdempotencyKeyEntry;
import com.example.mockbackend.repository.IdempotencyKeyRepository;
import com.example.mockbackend.service.JobService;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * H2 file database (PLAN stage 0): what a running server knew must still be there after a restart.
 * A restart is simulated by opening two Spring contexts, one after the other, on the same database file.
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
                        "--mock.worker.pending-ms=50",
                        "--mock.worker.processing-ms=50");
    }

    private static MockMultipartFile jpeg(String name, int bytes) {
        return new MockMultipartFile("photos", name, "image/jpeg", new byte[bytes]);
    }
}
