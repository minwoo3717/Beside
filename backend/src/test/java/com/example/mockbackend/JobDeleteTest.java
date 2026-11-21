package com.example.mockbackend;

import com.example.mockbackend.domain.JobRun;
import com.example.mockbackend.domain.ShareLink;
import com.example.mockbackend.domain.StoredUpload;
import com.example.mockbackend.repository.IdempotencyKeyRepository;
import com.example.mockbackend.repository.JobRepository;
import com.example.mockbackend.repository.JobRunRepository;
import com.example.mockbackend.repository.ShareLinkRepository;
import com.example.mockbackend.service.ThumbnailService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DELETE /api/v1/jobs/{id} against the mock worker: a finished job loses its photos, its result folder, its thumbnail,
 * its share links and its Idempotency-Key, but its job_runs rows stay (they are measurements). The mock serves one
 * shared sample GLB for every job, which must survive. PENDING lasts long enough here to try deleting a waiting job.
 */
@SpringBootTest
@AutoConfigureMockMvc
class JobDeleteTest {
    private static final long PENDING_MS = 1_000;

    @TempDir
    static Path storage;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("storage.upload.dir", () -> storage.resolve("uploads").toString());
        registry.add("storage.result.dir", () -> storage.resolve("results").toString());
        registry.add("storage.thumb.dir", () -> storage.resolve("thumbs").toString());
        registry.add("storage.result.sample", () -> sample().toString());
        registry.add("mock.worker.pending-ms", () -> String.valueOf(PENDING_MS));
        registry.add("mock.worker.processing-ms", () -> "100");
    }

    @BeforeAll
    static void writeSample() throws IOException {
        Files.write(sample(), "glTF delete sample".getBytes(StandardCharsets.UTF_8));
    }

    private static Path sample() {
        return storage.resolve("sample-dog.glb");
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JobRepository jobs;

    @Autowired
    JobRunRepository jobRuns;

    @Autowired
    ShareLinkRepository shareLinks;

    @Autowired
    IdempotencyKeyRepository idempotencyKeys;

    @Autowired
    ThumbnailService thumbnails;

    @Test
    void deletingAFinishedJobRemovesFilesLinksAndKeyButKeepsItsRuns() throws Exception {
        String key = "delete-key-" + UUID.randomUUID();
        String jobId = json(mvc.perform(multipart("/api/v1/jobs")
                        .file(new MockMultipartFile("photos", "dog.jpg", "image/jpeg", TestImages.jpeg(300, 200)))
                        .file(new MockMultipartFile("photos", "dog-2.png", "image/png", TestImages.pngWithAlpha(40, 40)))
                        .header("Idempotency-Key", key))
                .andExpect(status().isAccepted()).andReturn()).path("jobId").asText();
        awaitStatus(jobId, "COMPLETED");

        List<Path> photos = jobs.findById(jobId).orElseThrow().getUploads().stream().map(u -> Path.of(u.path())).toList();
        assertThat(photos).hasSize(2).allSatisfy(photo -> assertThat(Files.isRegularFile(photo)).isTrue());
        Path thumb = storage.resolve("thumbs").resolve(jobId + ".jpg");
        assertThat(thumbnails.drain(Duration.ofSeconds(10))).as("queued thumbnail made").isTrue();
        assertThat(Files.isRegularFile(thumb)).isTrue();
        // What the real worker leaves: {storage.result.dir}/{jobId}/base.glb.
        Path resultFolder = Files.createDirectories(storage.resolve("results").resolve(jobId));
        Files.write(resultFolder.resolve("base.glb"), "glTF real result".getBytes(StandardCharsets.UTF_8));
        String token = json(mvc.perform(post("/api/v1/jobs/{id}/shares", jobId))
                .andExpect(status().isCreated()).andReturn()).path("token").asText();
        assertThat(idempotencyKeys.findById(key)).isPresent();
        List<JobRun> runsBefore = jobRuns.findByJobIdOrderByAttemptAsc(jobId);
        assertThat(runsBefore).hasSize(1);

        mvc.perform(delete("/api/v1/jobs/{id}", jobId))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        mvc.perform(get("/api/v1/jobs/{id}", jobId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("JOB_NOT_FOUND"));
        assertThat(jobs.findById(jobId)).isEmpty();
        assertThat(photos).allSatisfy(photo -> assertThat(photo).doesNotExist());
        assertThat(thumb).doesNotExist();
        assertThat(resultFolder).doesNotExist();
        assertThat(sample()).as("the mock's shared sample GLB is not this job's file").isRegularFile();

        mvc.perform(get("/api/v1/shares/{token}", token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SHARE_NOT_FOUND"));
        assertThat(shareLinks.findById(token)).get().extracting(ShareLink::getRevokedAt).isNotNull();

        assertThat(idempotencyKeys.findById(key)).as("the key no longer points at the deleted job").isEmpty();
        String again = json(mvc.perform(multipart("/api/v1/jobs")
                        .file(new MockMultipartFile("photos", "dog.jpg", "image/jpeg", new byte[]{1, 2, 3}))
                        .header("Idempotency-Key", key))
                .andExpect(status().isAccepted()).andReturn()).path("jobId").asText();
        assertThat(again).as("same key, new job").isNotEqualTo(jobId);

        assertThat(jobRuns.findByJobIdOrderByAttemptAsc(jobId)).as("job_runs are measurements and stay")
                .extracting(JobRun::result).containsExactly("COMPLETED");

        mvc.perform(delete("/api/v1/jobs/{id}", jobId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("JOB_NOT_FOUND"))
                .andExpect(jsonPath("$.error.jobId").value(jobId));
    }

    @Test
    void aWaitingJobCannotBeDeletedUntilItIsCanceled() throws Exception {
        String jobId = upload("wait.jpg");
        mvc.perform(delete("/api/v1/jobs/{id}", jobId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("JOB_IN_PROGRESS"))
                .andExpect(jsonPath("$.error.jobId").value(jobId));
        assertThat(readJob(jobId).path("status").asText()).as("nothing happened to it").isEqualTo("PENDING");
        List<StoredUpload> uploads = jobs.findById(jobId).orElseThrow().getUploads();
        assertThat(Path.of(uploads.get(0).path())).isRegularFile();

        mvc.perform(post("/api/v1/jobs/{id}/cancel", jobId)).andExpect(status().isOk());
        mvc.perform(delete("/api/v1/jobs/{id}", jobId)).andExpect(status().isNoContent());
        assertThat(Path.of(uploads.get(0).path())).doesNotExist();
        assertThat(jobRuns.findByJobIdOrderByAttemptAsc(jobId)).extracting(JobRun::result).containsExactly("FAILED:CANCELED");

        // The canceled attempt's worker wakes up after the PENDING time and finds no job: nothing comes back.
        await().during(Duration.ofMillis(PENDING_MS + 400)).atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> assertThat(jobs.findById(jobId)).isEmpty());
    }

    @Test
    void aFailedJobCanBeDeleted() throws Exception {
        String jobId = upload("fail-dog.jpg");
        awaitStatus(jobId, "FAILED");
        mvc.perform(delete("/api/v1/jobs/{id}", jobId)).andExpect(status().isNoContent());
        assertThat(jobs.findById(jobId)).isEmpty();
        assertThat(jobRuns.findByJobIdOrderByAttemptAsc(jobId)).extracting(JobRun::getErrorCode).containsExactly("INFERENCE_FAILED");
    }

    @Test
    void ratingsOfADeletedJobStayInTheMeasurementTable() throws Exception {
        String jobId = upload("dog.jpg");
        awaitStatus(jobId, "COMPLETED");
        mvc.perform(put("/api/v1/jobs/{id}/ratings/{rater}", jobId, "minwoo")
                        .contentType("application/json")
                        .content("{\"faceShape\":4,\"ears\":3,\"bodyProportion\":4,\"furColor\":5,\"pattern\":3,\"textureArtifacts\":4}"))
                .andExpect(status().isOk());

        mvc.perform(delete("/api/v1/jobs/{id}", jobId)).andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/jobs/{id}/ratings", jobId)).andExpect(status().isNotFound());
        String csv = mvc.perform(get("/api/v1/admin/ratings").param("format", "csv"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv).as("a human rating is a measurement, like job_runs").contains(jobId).contains("minwoo");
    }

    // ------------------------------------------------------------------ helpers

    private String upload(String filename) throws Exception {
        return json(mvc.perform(multipart("/api/v1/jobs")
                        .file(new MockMultipartFile("photos", filename, "image/jpeg", new byte[]{1, 2, 3})))
                .andExpect(status().isAccepted()).andReturn()).path("jobId").asText();
    }

    private void awaitStatus(String jobId, String expected) {
        await().pollInterval(Duration.ofMillis(50)).atMost(Duration.ofSeconds(8))
                .untilAsserted(() -> assertThat(readJob(jobId).path("status").asText()).isEqualTo(expected));
    }

    private JsonNode readJob(String jobId) throws Exception {
        return json(mvc.perform(get("/api/v1/jobs/{id}", jobId)).andExpect(status().isOk()).andReturn());
    }

    private JsonNode json(MvcResult result) throws IOException {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }
}
