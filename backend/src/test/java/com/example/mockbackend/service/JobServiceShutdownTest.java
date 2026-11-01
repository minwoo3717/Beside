package com.example.mockbackend.service;

import com.example.mockbackend.domain.Job;
import com.example.mockbackend.domain.JobStatus;
import com.example.mockbackend.repository.IdempotencyKeyRepository;
import com.example.mockbackend.repository.JobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

/**
 * Once shutdown begins, Spring's task executor rejects new work while Tomcat still answers requests. A job whose
 * dispatch is rejected must not stay PENDING with no worker (until the next start fails it as INTERNAL_ERROR).
 */
@SpringBootTest
class JobServiceShutdownTest {

    @TempDir
    static Path storage;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("storage.upload.dir", () -> storage.resolve("uploads").toString());
        registry.add("storage.result.dir", () -> storage.resolve("results").toString());
    }

    @MockBean
    JobWorker jobWorker;

    @Autowired
    JobServiceImpl jobService;

    @Autowired
    JobRepository jobRepository;

    @Autowired
    IdempotencyKeyRepository idempotencyKeys;

    @BeforeEach
    void executorIsShuttingDown() {
        doThrow(new TaskRejectedException("executor shut down")).when(jobWorker).dispatch(anyString());
    }

    @Test
    void aNewJobIsRemovedWithItsPhotosAndKey() throws IOException {
        long before = jobRepository.count();
        MockMultipartFile photo = new MockMultipartFile("photos", "late.png", "image/png", new byte[]{1, 2, 3});
        assertThatThrownBy(() -> jobService.submitJobV1(List.of(photo), "late-key"))
                .isInstanceOf(TaskRejectedException.class);
        assertThat(jobRepository.count()).isEqualTo(before);
        assertThat(idempotencyKeys.findById("late-key")).isEmpty();
        try (Stream<Path> files = Files.list(storage.resolve("uploads"))) {
            assertThat(files.filter(file -> file.getFileName().toString().endsWith("_late.png"))).isEmpty();
        }
    }

    @Test
    void aRetriedJobGoesBackToTheFailedAttemptItHad() {
        Instant then = Instant.now().minusSeconds(60);
        Job failed = new Job();
        failed.setId(UUID.randomUUID().toString());
        failed.setStatus(JobStatus.FAILED);
        failed.setCreatedAt(then);
        failed.setUpdatedAt(then);
        failed.setQueuedAt(then);
        failed.setFinishedAt(then);
        failed.setAttempt(1);
        failed.setErrorCode("INFERENCE_FAILED");
        failed.setErrorMessage("first attempt");
        jobRepository.save(failed);

        assertThatThrownBy(() -> jobService.retry(failed.getId())).isInstanceOf(TaskRejectedException.class);
        Job after = jobRepository.findById(failed.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(after.getAttempt()).isEqualTo(1);
        assertThat(after.getErrorCode()).isEqualTo("INFERENCE_FAILED");
        assertThat(after.getErrorMessage()).isEqualTo("first attempt");
    }
}
