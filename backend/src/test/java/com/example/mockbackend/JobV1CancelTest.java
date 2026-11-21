package com.example.mockbackend;

import com.example.mockbackend.domain.JobRun;
import com.example.mockbackend.repository.JobRunRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /api/v1/jobs/{id}/cancel against the mock worker. PENDING lasts long here so a test can cancel before the
 * worker starts: a canceled job ends FAILED (error.code CANCELED) and the worker never moves it to PROCESSING, only
 * PENDING jobs can be canceled (409 JOB_NOT_PENDING), and a canceled job retries from the start. The mock fills
 * JobResponse.queue like real does (no GPU, so never a reason). Queue order and estimates: RealJobQueueTest.
 */
@SpringBootTest
@AutoConfigureMockMvc
class JobV1CancelTest {
    private static final long PENDING_MS = 1_200;

    @TempDir
    static Path storage;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("storage.upload.dir", () -> storage.resolve("uploads").toString());
        registry.add("storage.result.sample", () -> storage.resolve("sample-dog.glb").toString());
        registry.add("mock.worker.pending-ms", () -> String.valueOf(PENDING_MS));
        registry.add("mock.worker.processing-ms", () -> "100");
    }

    @BeforeAll
    static void writeSample() throws IOException {
        Files.write(storage.resolve("sample-dog.glb"), "glTF mock binary".getBytes(StandardCharsets.UTF_8));
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JobRunRepository jobRuns;

    @Test
    void cancelingAPendingJobEndsItAndTheWorkerNeverStartsIt() throws Exception {
        String jobId = create();
        JsonNode pending = readJob(jobId);
        assertThat(pending.path("status").asText()).isEqualTo("PENDING");
        assertThat(pending.path("queue").path("ahead").isInt()).as("queue while PENDING").isTrue();
        assertThat(pending.path("queue").has("reason")).isTrue();
        assertThat(pending.path("queue").path("reason").isNull()).as("the mock has no GPU to wait for").isTrue();

        JsonNode canceled = json(mvc.perform(post("/api/v1/jobs/{id}/cancel", jobId))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn());
        assertThat(canceled.path("id").asText()).isEqualTo(jobId);
        assertThat(canceled.path("status").asText()).isEqualTo("FAILED");
        assertThat(canceled.path("error").path("code").asText()).isEqualTo("CANCELED");
        assertThat(canceled.path("error").path("message").asText()).isNotBlank();
        assertThat(canceled.path("progress").isNull()).isTrue();
        assertThat(canceled.path("asset").isNull()).isTrue();
        assertThat(canceled.has("queue")).isTrue();
        assertThat(canceled.path("queue").isNull()).as("queue only while PENDING").isTrue();
        assertThat(canceled.path("estimate").isNull()).as("estimate only while PENDING or PROCESSING").isTrue();
        assertThat(canceled.path("timings").path("processingMs").isNull()).as("never started").isTrue();
        assertThat(canceled.path("timings").path("totalMs").isNumber()).isTrue();

        // Well past the mock's PENDING time: the worker re-reads the job and does not start it.
        await().during(Duration.ofMillis(PENDING_MS + 600)).atMost(Duration.ofSeconds(6))
                .pollInterval(Duration.ofMillis(50))
                .untilAsserted(() -> {
                    JsonNode now = readJob(jobId);
                    assertThat(now.path("status").asText()).isEqualTo("FAILED");
                    assertThat(now.path("error").path("code").asText()).isEqualTo("CANCELED");
                });

        // One measured run, FAILED:CANCELED (job_runs.error_code = 'CANCELED'), so statistics can leave it out.
        assertThat(jobRuns.findByJobIdOrderByAttemptAsc(jobId)).singleElement().satisfies(run -> {
            assertThat(run.result()).isEqualTo("FAILED:CANCELED");
            assertThat(run.getErrorCode()).isEqualTo("CANCELED");
            assertThat(run.getWorkerType()).isEqualTo("mock");
            assertThat(run.getProcessingMs()).isNull();
        });
    }

    @Test
    void onlyPendingJobsCanBeCanceled() throws Exception {
        String unknown = UUID.randomUUID().toString();
        mvc.perform(post("/api/v1/jobs/{id}/cancel", unknown))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("JOB_NOT_FOUND"))
                .andExpect(jsonPath("$.error.jobId").value(unknown));

        String completed = create();
        awaitStatus(completed, "COMPLETED");
        mvc.perform(post("/api/v1/jobs/{id}/cancel", completed))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("JOB_NOT_PENDING"))
                .andExpect(jsonPath("$.error.jobId").value(completed));
        JsonNode stillCompleted = readJob(completed);
        assertThat(stillCompleted.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(stillCompleted.path("queue").isNull()).isTrue();
        assertThat(stillCompleted.path("estimate").isNull()).isTrue();

        // Canceling twice: the second is a conflict and the first result stays.
        String canceled = create();
        mvc.perform(post("/api/v1/jobs/{id}/cancel", canceled)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/jobs/{id}/cancel", canceled))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("JOB_NOT_PENDING"));
        assertThat(readJob(canceled).path("error").path("code").asText()).isEqualTo("CANCELED");
    }

    @Test
    void aCanceledJobRetriesFromTheStart() throws Exception {
        String jobId = create();
        mvc.perform(post("/api/v1/jobs/{id}/cancel", jobId)).andExpect(status().isOk());
        Thread.sleep(PENDING_MS / 2);

        mvc.perform(post("/api/v1/jobs/{id}/retry", jobId)).andExpect(status().isAccepted());
        JsonNode retried = readJob(jobId);
        assertThat(retried.path("status").asText()).isEqualTo("PENDING");
        assertThat(retried.path("error").isNull()).isTrue();
        assertThat(retried.path("queue").isObject()).isTrue();

        awaitStatus(jobId, "COMPLETED");
        // Started by the retry's own dispatch after a full PENDING time, not by the first attempt's worker, which
        // woke up while the retry was PENDING and had to leave the new attempt alone.
        assertThat(readJob(jobId).path("timings").path("queuedMs").asLong()).isGreaterThanOrEqualTo(PENDING_MS - 20);
        assertThat(jobRuns.findByJobIdOrderByAttemptAsc(jobId)).extracting(JobRun::result)
                .containsExactly("FAILED:CANCELED", "COMPLETED");
    }

    // ------------------------------------------------------------------ helpers

    private String create() throws Exception {
        MvcResult created = mvc.perform(multipart("/api/v1/jobs")
                        .file(new MockMultipartFile("photos", "dog.jpg", "image/jpeg", new byte[]{1, 2, 3})))
                .andExpect(status().isAccepted())
                .andReturn();
        return json(created).path("jobId").asText();
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
