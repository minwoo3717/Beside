package com.example.mockbackend;

import com.example.mockbackend.domain.JobRun;
import com.example.mockbackend.domain.ShareLink;
import com.example.mockbackend.domain.StoredUpload;
import com.example.mockbackend.repository.IdempotencyKeyRepository;
import com.example.mockbackend.repository.JobRepository;
import com.example.mockbackend.repository.JobRunRepository;
import com.example.mockbackend.repository.ShareLinkRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockPart;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Deleting jobs, which API v1.2 does only through the library: DELETE /api/v1/pets/{id}?withJobs=true
 * (JobDeletionService). The review branch's DELETE /api/v1/jobs/{id}, GET /api/v1/jobs/{id}/thumbnail and
 * GET /api/v1/jobs/{id}/photos/{index} were not taken (REVIEW_CHECKLIST #11, 2026-11-21) and are not served.
 * A deleted job loses its photos, its result folder, its share links and its Idempotency-Key, but its job_runs rows
 * and quality ratings stay (they are measurements). The mock serves one shared sample GLB for every job, which must
 * survive. PENDING lasts long enough here to try deleting a waiting job.
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

    @Test
    void deletingAPetWithItsJobsRemovesFilesLinksAndKeyButKeepsRunsAndRatings() throws Exception {
        String petId = createPet("정리");
        String key = "delete-key-" + UUID.randomUUID();
        String jobId = json(mvc.perform(multipart("/api/v1/jobs")
                        .file(new MockMultipartFile("photos", "dog.jpg", "image/jpeg", new byte[]{1, 2, 3}))
                        .file(new MockMultipartFile("photos", "dog-2.png", "image/png", new byte[]{4, 5, 6}))
                        .part(new MockPart("petId", petId.getBytes(StandardCharsets.UTF_8)))
                        .header("Idempotency-Key", key))
                .andExpect(status().isAccepted()).andReturn()).path("jobId").asText();
        awaitStatus(jobId, "COMPLETED");

        List<Path> photos = jobs.findById(jobId).orElseThrow().getUploads().stream().map(u -> Path.of(u.path())).toList();
        assertThat(photos).hasSize(2).allSatisfy(photo -> assertThat(Files.isRegularFile(photo)).isTrue());
        // What the real worker leaves: {storage.result.dir}/{jobId}/base.glb.
        Path resultFolder = Files.createDirectories(storage.resolve("results").resolve(jobId));
        Files.write(resultFolder.resolve("base.glb"), "glTF real result".getBytes(StandardCharsets.UTF_8));
        String token = json(mvc.perform(post("/api/v1/jobs/{id}/shares", jobId))
                .andExpect(status().isCreated()).andReturn()).path("token").asText();
        mvc.perform(put("/api/v1/jobs/{id}/ratings/{rater}", jobId, "minwoo")
                        .contentType("application/json")
                        .content("{\"faceShape\":4,\"ears\":3,\"bodyProportion\":4,\"furColor\":5,\"pattern\":3,\"textureArtifacts\":4}"))
                .andExpect(status().isOk());
        assertThat(idempotencyKeys.findById(key)).isPresent();
        assertThat(jobRuns.findByJobIdOrderByAttemptAsc(jobId)).hasSize(1);

        mvc.perform(delete("/api/v1/pets/{id}", petId).param("withJobs", "true"))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/jobs/{id}", jobId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("JOB_NOT_FOUND"));
        assertThat(jobs.findById(jobId)).isEmpty();
        assertThat(photos).allSatisfy(photo -> assertThat(photo).doesNotExist());
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
        mvc.perform(get("/api/v1/jobs/{id}/ratings", jobId)).andExpect(status().isNotFound());
        String csv = mvc.perform(get("/api/v1/admin/ratings").param("format", "csv"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv).as("a human rating is a measurement, like job_runs").contains(jobId).contains("minwoo");
    }

    @Test
    void aWaitingJobBlocksDeletingItsPetUntilItIsCanceled() throws Exception {
        String petId = createPet("대기");
        String jobId = upload("wait.jpg", petId);
        mvc.perform(delete("/api/v1/pets/{id}", petId).param("withJobs", "true"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("JOB_IN_PROGRESS"))
                .andExpect(jsonPath("$.error.jobId").value(jobId));
        assertThat(readJob(jobId).path("status").asText()).as("nothing happened to it").isEqualTo("PENDING");
        List<StoredUpload> uploads = jobs.findById(jobId).orElseThrow().getUploads();
        assertThat(Path.of(uploads.get(0).path())).isRegularFile();

        mvc.perform(post("/api/v1/jobs/{id}/cancel", jobId)).andExpect(status().isOk());
        mvc.perform(delete("/api/v1/pets/{id}", petId).param("withJobs", "true")).andExpect(status().isNoContent());
        assertThat(Path.of(uploads.get(0).path())).doesNotExist();
        assertThat(jobRuns.findByJobIdOrderByAttemptAsc(jobId)).extracting(JobRun::result).containsExactly("FAILED:CANCELED");

        // The canceled attempt's worker wakes up after the PENDING time and finds no job: nothing comes back.
        await().during(Duration.ofMillis(PENDING_MS + 400)).atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> assertThat(jobs.findById(jobId)).isEmpty());
    }

    @Test
    void aFailedJobIsDeletedWithItsPet() throws Exception {
        String petId = createPet("실패");
        String jobId = upload("fail-dog.jpg", petId);
        awaitStatus(jobId, "FAILED");
        mvc.perform(delete("/api/v1/pets/{id}", petId).param("withJobs", "true")).andExpect(status().isNoContent());
        assertThat(jobs.findById(jobId)).isEmpty();
        assertThat(jobRuns.findByJobIdOrderByAttemptAsc(jobId)).extracting(JobRun::getErrorCode).containsExactly("INFERENCE_FAILED");
    }

    /**
     * Not served in API v1.2 (#11): no route answers DELETE on a job (405, v1 envelope INVALID_REQUEST, the job stays)
     * and the thumbnail and photo paths are unknown v1 paths (404 NOT_FOUND).
     */
    @Test
    void theEndpointsLeftOutOfV12AreNotServed() throws Exception {
        String jobId = upload("dog.jpg", null);
        awaitStatus(jobId, "COMPLETED");

        mvc.perform(delete("/api/v1/jobs/{id}", jobId))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        assertThat(readJob(jobId).path("status").asText()).isEqualTo("COMPLETED");
        assertThat(readJob(jobId).has("thumbnailUrl")).as("JobResponse has no thumbnailUrl").isFalse();

        for (String path : List.of("/api/v1/jobs/{id}/thumbnail", "/api/v1/jobs/{id}/photos/0")) {
            mvc.perform(get(path, jobId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        }
    }

    // ------------------------------------------------------------------ helpers

    private String createPet(String name) throws Exception {
        return json(mvc.perform(post("/api/v1/pets").contentType("application/json")
                        .content(objectMapper.writeValueAsString(java.util.Map.of("name", name))))
                .andExpect(status().isCreated()).andReturn()).path("id").asText();
    }

    /** POST /api/v1/jobs with the optional petId text part (null = no part). */
    private String upload(String filename, String petId) throws Exception {
        var request = multipart("/api/v1/jobs")
                .file(new MockMultipartFile("photos", filename, "image/jpeg", new byte[]{1, 2, 3}));
        if (petId != null) {
            request.part(new MockPart("petId", petId.getBytes(StandardCharsets.UTF_8)));
        }
        return json(mvc.perform(request).andExpect(status().isAccepted()).andReturn()).path("jobId").asText();
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
