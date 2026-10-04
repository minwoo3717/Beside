package com.example.mockbackend;

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
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Behaviour of API v1 against the mock worker (short delays). Shapes are asserted against docs/api/openapi.yaml
 * and docs/api/ERROR_CODES.md by hand here; JobV1ContractTest checks the generated schema.
 */
@SpringBootTest
@AutoConfigureMockMvc
class JobV1ApiTest {
    private static final byte[] SAMPLE_GLB = "glTF mock binary".getBytes(StandardCharsets.UTF_8);
    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    @TempDir
    static Path storage;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("storage.upload.dir", () -> storage.resolve("uploads").toString());
        registry.add("storage.result.sample", () -> storage.resolve("sample-dog.glb").toString());
        registry.add("mock.worker.pending-ms", () -> "150");
        registry.add("mock.worker.processing-ms", () -> "150");
    }

    @BeforeAll
    static void writeSample() throws IOException {
        Files.write(storage.resolve("sample-dog.glb"), SAMPLE_GLB);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void createsJobPollsToCompletionAndServesAsset() throws Exception {
        MvcResult created = upload(jpeg("dog-1.jpg", 3), jpeg("dog-2.jpg", 5))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").isString())
                .andReturn();
        String jobId = json(created).path("jobId").asText();
        assertThat(jobId).matches(UUID_PATTERN);
        assertThat(created.getResponse().getHeader("Location")).isEqualTo("/api/v1/jobs/" + jobId);

        JsonNode pending = readJob(jobId);
        assertThat(pending.path("status").asText()).isIn("PENDING", "PROCESSING");
        assertThat(pending.has("resultPath")).as("server paths are never exposed").isFalse();
        assertThat(pending.path("createdAt").asText()).isNotEmpty();
        assertThat(pending.path("updatedAt").asText()).isNotEmpty();
        assertThat(pending.path("timings").path("queuedMs").asLong()).isGreaterThanOrEqualTo(0);
        assertThat(pending.path("timings").path("totalMs").isNull()).isTrue();
        assertThat(pending.path("asset").isNull()).isTrue();
        assertThat(pending.path("error").isNull()).isTrue();
        assertThat(pending.path("uploadedFiles")).hasSize(2);
        assertThat(pending.path("uploadedFiles").get(0).path("name").asText()).isEqualTo("dog-1.jpg");
        assertThat(pending.path("uploadedFiles").get(0).path("bytes").asLong()).isEqualTo(3);
        assertThat(pending.path("uploadedFiles").get(0).has("path")).isFalse();

        mvc.perform(get("/api/v1/jobs/{id}/asset", jobId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("JOB_NOT_COMPLETED"))
                .andExpect(jsonPath("$.error.jobId").value(jobId));

        await().pollInterval(Duration.ofMillis(50)).atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(readJob(jobId).path("status").asText()).isEqualTo("COMPLETED"));

        JsonNode completed = readJob(jobId);
        assertThat(completed.path("progress").asDouble()).isEqualTo(1.0);
        assertThat(completed.path("timings").path("queuedMs").asLong()).isGreaterThanOrEqualTo(100);
        assertThat(completed.path("timings").path("processingMs").asLong()).isGreaterThanOrEqualTo(100);
        assertThat(completed.path("timings").path("totalMs").asLong()).isGreaterThanOrEqualTo(250);
        assertThat(completed.path("asset").path("url").asText()).isEqualTo("/api/v1/jobs/" + jobId + "/asset?variant=base");
        assertThat(completed.path("asset").path("bytes").asLong()).isEqualTo(SAMPLE_GLB.length);
        assertThat(completed.path("asset").path("variant").asText()).isEqualTo("base");
        assertThat(completed.path("asset").path("contentType").asText()).isEqualTo("model/gltf-binary");
        assertThat(completed.path("error").isNull()).isTrue();

        mvc.perform(get(completed.path("asset").path("url").asText()))
                .andExpect(status().isOk())
                .andExpect(content().contentType("model/gltf-binary"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"" + jobId + "-base.glb\""))
                .andExpect(header().longValue("Content-Length", SAMPLE_GLB.length))
                .andExpect(content().bytes(SAMPLE_GLB));
        mvc.perform(get("/api/v1/jobs/{id}/asset?variant=hair", jobId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ASSET_NOT_FOUND"))
                .andExpect(jsonPath("$.error.jobId").value(jobId));
        mvc.perform(get("/api/v1/jobs/{id}/asset?variant=fur", jobId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        mvc.perform(post("/api/v1/jobs/{id}/retry", jobId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("JOB_NOT_FAILED"))
                .andExpect(jsonPath("$.error.jobId").value(jobId));
    }

    @Test
    void failsWhenFilenameContainsTriggerAndCanBeRetried() throws Exception {
        String jobId = json(upload(jpeg("fail-dog.jpg", 4)).andExpect(status().isAccepted()).andReturn())
                .path("jobId").asText();

        await().pollInterval(Duration.ofMillis(50)).atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(readJob(jobId).path("status").asText()).isEqualTo("FAILED"));
        JsonNode failed = readJob(jobId);
        assertThat(failed.path("error").path("code").asText()).isEqualTo("INFERENCE_FAILED");
        assertThat(failed.path("error").path("message").asText()).isNotEmpty();
        assertThat(failed.path("progress").isNull()).isTrue();
        assertThat(failed.path("asset").isNull()).isTrue();
        assertThat(failed.path("timings").path("totalMs").asLong()).isGreaterThanOrEqualTo(250);
        mvc.perform(get("/api/v1/jobs/{id}/asset", jobId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("JOB_NOT_COMPLETED"));

        mvc.perform(post("/api/v1/jobs/{id}/retry", jobId))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/api/v1/jobs/" + jobId))
                .andExpect(jsonPath("$.jobId").value(jobId));
        JsonNode retried = readJob(jobId);
        assertThat(retried.path("status").asText()).isIn("PENDING", "PROCESSING");
        assertThat(retried.path("error").isNull()).isTrue();
        assertThat(retried.path("timings").path("totalMs").isNull()).isTrue();

        // The same photos fail again: the retry path is deterministic for the Unity failure flow.
        await().pollInterval(Duration.ofMillis(50)).atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(readJob(jobId).path("status").asText()).isEqualTo("FAILED"));
    }

    @Test
    void validatesUploadsAndUnknownJobs() throws Exception {
        mvc.perform(multipart("/api/v1/jobs"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("NO_PHOTOS"))
                .andExpect(jsonPath("$.error.message").isString());

        MockMultipartFile[] eleven = new MockMultipartFile[11];
        for (int i = 0; i < eleven.length; i++) {
            eleven[i] = jpeg("dog-" + i + ".jpg", 2);
        }
        upload(eleven).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("TOO_MANY_PHOTOS"));

        upload(new MockMultipartFile("photos", "dog.gif", "image/gif", new byte[]{1, 2}))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_IMAGE_TYPE"));

        upload(new MockMultipartFile("photos", "empty.jpg", "image/jpeg", new byte[0]))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        String unknown = UUID.randomUUID().toString();
        mvc.perform(get("/api/v1/jobs/{id}", unknown))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("JOB_NOT_FOUND"))
                .andExpect(jsonPath("$.error.jobId").value(unknown));
        mvc.perform(post("/api/v1/jobs/{id}/retry", unknown))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("JOB_NOT_FOUND"));
        mvc.perform(get("/api/v1/jobs/{id}/asset", unknown))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("JOB_NOT_FOUND"));

        mvc.perform(get("/api/v1/jobs?limit=0")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        mvc.perform(get("/api/v1/jobs?limit=abc")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        mvc.perform(get("/api/v1/jobs?cursor=not-a-job")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void replaysSameJobForSameIdempotencyKey() throws Exception {
        String key = "unity-" + UUID.randomUUID();
        String first = json(mvc.perform(multipart("/api/v1/jobs").file(jpeg("dog.jpg", 3)).header("Idempotency-Key", key))
                .andExpect(status().isAccepted()).andReturn()).path("jobId").asText();
        String second = json(mvc.perform(multipart("/api/v1/jobs").file(jpeg("dog.jpg", 3)).header("Idempotency-Key", key))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/api/v1/jobs/" + first))
                .andReturn()).path("jobId").asText();
        assertThat(second).isEqualTo(first);

        String other = json(mvc.perform(multipart("/api/v1/jobs").file(jpeg("dog.jpg", 3)).header("Idempotency-Key", key + "-b"))
                .andExpect(status().isAccepted()).andReturn()).path("jobId").asText();
        assertThat(other).isNotEqualTo(first);
    }

    @Test
    void listsJobsNewestFirstWithCursor() throws Exception {
        List<String> created = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            created.add(json(upload(jpeg("list-" + i + ".jpg", 2)).andExpect(status().isAccepted()).andReturn())
                    .path("jobId").asText());
        }

        JsonNode firstPage = json(mvc.perform(get("/api/v1/jobs?limit=2")).andExpect(status().isOk()).andReturn());
        assertThat(firstPage.path("items")).hasSize(2);
        assertThat(firstPage.path("nextCursor").asText()).isEqualTo(firstPage.path("items").get(1).path("id").asText());
        assertThat(firstPage.path("items").get(0).has("resultPath")).isFalse();

        List<String> seen = new ArrayList<>();
        String cursor = null;
        do {
            String url = "/api/v1/jobs?limit=2" + (cursor == null ? "" : "&cursor=" + cursor);
            JsonNode page = json(mvc.perform(get(url)).andExpect(status().isOk()).andReturn());
            page.path("items").forEach(item -> seen.add(item.path("id").asText()));
            cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").asText();
        } while (cursor != null);
        assertThat(seen).doesNotHaveDuplicates().containsAll(created);

        JsonNode all = json(mvc.perform(get("/api/v1/jobs?limit=100")).andExpect(status().isOk()).andReturn());
        assertThat(all.path("items").size()).isEqualTo(seen.size());
        assertThat(all.path("nextCursor").isNull()).isTrue();
    }

    @Test
    void reportsHealthWithProfileAndWorkerType() throws Exception {
        mvc.perform(get("/api/v1/healthz"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.profile").value("mock"))
                .andExpect(jsonPath("$.workerType").value("mock"));
    }

    @Test
    void wrapsV1ErrorsInEnvelopeButKeepsV0Flat() throws Exception {
        mvc.perform(get("/api/v1/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").isString());
        mvc.perform(get("/missing-page"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Resource not found"));
        mvc.perform(get("/api/jobs")).andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error").value("Invalid request (HTTP 405)"));
    }

    // ------------------------------------------------------------------ helpers

    private org.springframework.test.web.servlet.ResultActions upload(MockMultipartFile... files) throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart("/api/v1/jobs");
        for (MockMultipartFile file : files) {
            request = request.file(file);
        }
        return mvc.perform(request);
    }

    private static MockMultipartFile jpeg(String name, int bytes) {
        return new MockMultipartFile("photos", name, "image/jpeg", new byte[bytes]);
    }

    private JsonNode readJob(String jobId) throws Exception {
        return json(mvc.perform(get("/api/v1/jobs/{id}", jobId)).andExpect(status().isOk()).andReturn());
    }

    private JsonNode json(MvcResult result) throws IOException {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }
}
