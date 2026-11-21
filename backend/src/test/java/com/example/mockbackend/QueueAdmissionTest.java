package com.example.mockbackend;

import com.example.mockbackend.domain.Job;
import com.example.mockbackend.domain.JobStatus;
import com.example.mockbackend.repository.IdempotencyKeyEntry;
import com.example.mockbackend.repository.IdempotencyKeyRepository;
import com.example.mockbackend.repository.JobRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Queue cap (REVIEW_CHECKLIST #21, decided 2026-11-21; admission.max-pending, here 2): while that
 * many jobs are PENDING, POST /api/v1/jobs and POST /api/v1/jobs/{id}/retry answer 503 QUEUE_FULL with Retry-After and
 * create or change nothing; everything else answers as without the cap. The queue is filled with PENDING rows written
 * straight to the database, so no worker sleeps through the test. A test that gets a 202 waits for the mock worker to
 * end the job: the worker saves the job again, which would bring the row back after the cleanup.
 */
@SpringBootTest
@AutoConfigureMockMvc
class QueueAdmissionTest {
    @TempDir
    static Path storage;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("storage.upload.dir", () -> storage.resolve("uploads").toString());
        registry.add("mock.worker.pending-ms", () -> "50");
        registry.add("mock.worker.processing-ms", () -> "50");
        registry.add("admission.max-pending", () -> "2");
        registry.add("admission.retry-after-seconds", () -> "45");
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JobRepository jobRepository;

    @Autowired
    IdempotencyKeyRepository idempotencyKeys;

    @Autowired
    MeterRegistry registry;

    @AfterEach
    void clear() {
        idempotencyKeys.deleteAll();
        jobRepository.deleteAll();
    }

    @Test
    void createIsRefusedWithRetryAfterWhenTheQueueIsFull() throws Exception {
        fillTheQueue();
        double refusedCreates = refused("create");
        double refusedRetries = refused("retry");
        int stored = storedPhotos(); // other tests of this class leave the photos of accepted jobs here

        mvc.perform(multipart("/api/v1/jobs").file(photo()).header("Idempotency-Key", "key-full"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "45"))
                .andExpect(jsonPath("$.error.code").value("QUEUE_FULL"))
                .andExpect(jsonPath("$.error.jobId").doesNotExist());

        assertThat(jobRepository.count()).as("no job is created for a refused request").isEqualTo(2);
        assertThat(storedPhotos()).as("no photo is stored").isEqualTo(stored);
        assertThat(idempotencyKeys.findById("key-full")).as("no key is linked").isEmpty();
        assertThat(refused("create") - refusedCreates).isEqualTo(1.0);
        assertThat(refused("retry") - refusedRetries).isZero();
    }

    @Test
    void createIsAcceptedBelowTheCap() throws Exception {
        jobRepository.save(job(JobStatus.PENDING));
        jobRepository.save(job(JobStatus.PROCESSING)); // only PENDING jobs wait in the queue

        awaitEnd(accepted(mvc.perform(multipart("/api/v1/jobs").file(photo()))));
    }

    @Test
    void sameIdempotencyKeyStillGetsItsJobWhenTheQueueIsFull() throws Exception {
        Job mine = jobRepository.save(job(JobStatus.PENDING));
        jobRepository.save(job(JobStatus.PENDING));
        idempotencyKeys.save(new IdempotencyKeyEntry("key-1", mine.getId(), Instant.now()));

        mvc.perform(multipart("/api/v1/jobs").file(photo()).header("Idempotency-Key", "key-1"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value(mine.getId()));

        mvc.perform(multipart("/api/v1/jobs").file(photo()).header("Idempotency-Key", "key-2"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("QUEUE_FULL"));
        assertThat(idempotencyKeys.findById("key-2")).as("a refused request links no key").isEmpty();
    }

    @Test
    void retryIsRefusedAndTheJobStaysFailedWhenTheQueueIsFull() throws Exception {
        fillTheQueue();
        Job failed = jobRepository.save(job(JobStatus.FAILED));
        double refusedRetries = refused("retry");

        mvc.perform(post("/api/v1/jobs/{id}/retry", failed.getId()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "45"))
                .andExpect(jsonPath("$.error.code").value("QUEUE_FULL"))
                .andExpect(jsonPath("$.error.jobId").value(failed.getId())); // decision D7: the job it refused

        Job after = jobRepository.findById(failed.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(after.getAttempt()).isEqualTo(1);
        assertThat(after.getErrorCode()).isEqualTo("INFERENCE_FAILED");
        assertThat(refused("retry") - refusedRetries).isEqualTo(1.0);
    }

    @Test
    void retryOfAJobThatIsNotFailedIsStill409WhenTheQueueIsFull() throws Exception {
        Job pending = jobRepository.save(job(JobStatus.PENDING));
        jobRepository.save(job(JobStatus.PENDING));

        mvc.perform(post("/api/v1/jobs/{id}/retry", pending.getId()))
                .andExpect(status().isConflict())
                .andExpect(header().doesNotExist("Retry-After"))
                .andExpect(jsonPath("$.error.code").value("JOB_NOT_FAILED"));
    }

    @Test
    void retryOfAnUnknownJobIsStill404WhenTheQueueIsFull() throws Exception {
        fillTheQueue();

        mvc.perform(post("/api/v1/jobs/{id}/retry", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound())
                .andExpect(header().doesNotExist("Retry-After"))
                .andExpect(jsonPath("$.error.code").value("JOB_NOT_FOUND"));
        mvc.perform(get("/api/v1/jobs/{id}", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound())
                .andExpect(header().doesNotExist("Retry-After"));
    }

    @Test
    void invalidUploadIsStill400WhenTheQueueIsFull() throws Exception {
        fillTheQueue();

        mvc.perform(multipart("/api/v1/jobs")
                        .file(new MockMultipartFile("photos", "a.gif", "image/gif", new byte[]{1, 2, 3})))
                .andExpect(status().isBadRequest())
                .andExpect(header().doesNotExist("Retry-After"))
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_IMAGE_TYPE"));
    }

    @Test
    void v0IsNotCappedAndKeepsItsFlatErrors() throws Exception {
        fillTheQueue();
        double refusedCreates = refused("create");

        // Frozen v0 /api/jobs: accepted as before, even though the v1 queue is full (Caddy keeps it off the internet).
        String location = mvc.perform(multipart("/api/jobs").file(photo()))
                .andExpect(status().isAccepted())
                .andExpect(header().doesNotExist("Retry-After"))
                .andReturn().getResponse().getHeader("Location");
        assertThat(location).startsWith("/api/jobs/");
        awaitEnd(location.substring("/api/jobs/".length()));

        mvc.perform(multipart("/api/jobs").file(new MockMultipartFile("other", "x.jpg", "image/jpeg", new byte[]{1})))
                .andExpect(status().isBadRequest())
                .andExpect(header().doesNotExist("Retry-After"))
                .andExpect(jsonPath("$.error").isString());
        assertThat(refused("create")).isEqualTo(refusedCreates);
    }

    @Test
    void aRetryBelowTheCapIsAcceptedAsTheNextAttempt() throws Exception {
        jobRepository.save(job(JobStatus.PENDING));
        Job failed = jobRepository.save(job(JobStatus.FAILED));

        mvc.perform(post("/api/v1/jobs/{id}/retry", failed.getId()))
                .andExpect(status().isAccepted())
                .andExpect(header().doesNotExist("Retry-After"))
                .andExpect(jsonPath("$.jobId").value(failed.getId()));

        // The mock worker may already have moved it on; the attempt number is what this request wrote.
        assertThat(jobRepository.findById(failed.getId()).orElseThrow().getAttempt()).isEqualTo(2);
        awaitEnd(failed.getId());
    }

    @Test
    void pendingRowsOlderThanStaleAfterDoNotHoldPlaces() throws Exception {
        // admission.stale-after-ms unset: the longest a job can wait as PENDING here (cap 2, no offline wait, default
        // /infer timeout 2 min: 2 x 2 x 2 min + 10 min = 18 min). Rows a day old are taken as lost, not as waiting.
        for (int i = 0; i < 2; i++) {
            Job lost = job(JobStatus.PENDING);
            lost.setQueuedAt(Instant.now().minus(Duration.ofDays(1)));
            jobRepository.save(lost);
        }

        awaitEnd(accepted(mvc.perform(multipart("/api/v1/jobs").file(photo()))));
    }

    private void fillTheQueue() {
        jobRepository.save(job(JobStatus.PENDING));
        jobRepository.save(job(JobStatus.PENDING));
    }

    private double refused(String op) {
        return registry.get("beside.jobs.refused").tag("op", op).counter().count();
    }

    private static String accepted(ResultActions result) throws Exception {
        String body = result.andExpect(status().isAccepted()).andExpect(header().doesNotExist("Retry-After"))
                .andReturn().getResponse().getContentAsString();
        return body.replaceAll(".*\"jobId\"\\s*:\\s*\"([^\"]+)\".*", "$1");
    }

    /** Waits until the mock worker ended the job, so it does not save the row again after the cleanup. */
    private void awaitEnd(String jobId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (true) {
            JobStatus status = jobRepository.findById(jobId).map(Job::getStatus).orElse(null);
            if (status == JobStatus.COMPLETED || status == JobStatus.FAILED) {
                return;
            }
            assertThat(System.currentTimeMillis()).as("job %s did not end (%s)", jobId, status).isLessThan(deadline);
            Thread.sleep(20);
        }
    }

    private static int storedPhotos() {
        String[] names = storage.resolve("uploads").toFile().list();
        return names == null ? 0 : names.length;
    }

    private static MockMultipartFile photo() {
        return new MockMultipartFile("photos", "dog.jpg", "image/jpeg", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1});
    }

    private static Job job(JobStatus status) {
        Instant now = Instant.now();
        Job job = new Job();
        job.setId(UUID.randomUUID().toString());
        job.setStatus(status);
        job.setCreatedAt(now);
        job.setUpdatedAt(now);
        job.setQueuedAt(now);
        job.setProgress(0.0);
        job.setAttempt(1);
        if (status == JobStatus.FAILED) {
            job.setFinishedAt(now);
            job.setErrorCode("INFERENCE_FAILED");
            job.setErrorMessage("queue admission test");
        }
        return job;
    }
}
