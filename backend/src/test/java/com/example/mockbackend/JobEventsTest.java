package com.example.mockbackend;

import com.example.mockbackend.domain.JobRun;
import com.example.mockbackend.domain.JobStatus;
import com.example.mockbackend.repository.JobRunRepository;
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
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Server measurement log (docs/METRICS.md §1): one events.jsonl line per job that ends COMPLETED or FAILED,
 * with the metric names the three tracks share.
 */
@SpringBootTest
@AutoConfigureMockMvc
class JobEventsTest {
    @TempDir
    static Path storage;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("storage.upload.dir", () -> storage.resolve("uploads").toString());
        registry.add("storage.result.sample", () -> storage.resolve("sample-dog.glb").toString());
        registry.add("storage.events.path", () -> storage.resolve("events").resolve("events.jsonl").toString());
        registry.add("mock.worker.pending-ms", () -> "100");
        registry.add("mock.worker.processing-ms", () -> "100");
    }

    @BeforeAll
    static void writeSample() throws IOException {
        Files.write(storage.resolve("sample-dog.glb"), "glTF".getBytes(StandardCharsets.UTF_8));
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JobRunRepository jobRuns;

    @Test
    void appendsOneLinePerFinishedJobWithSharedMetricNames() throws Exception {
        String completed = create(jpeg("dog-1.jpg", 3), jpeg("dog-2.jpg", 5));
        String failed = create(jpeg("fail-dog.jpg", 4));
        awaitStatus(completed, "COMPLETED");
        awaitStatus(failed, "FAILED");

        // A retry that fails again is a second finished run: a second line, attempt 2.
        mvc.perform(post("/api/v1/jobs/{id}/retry", failed)).andExpect(status().isAccepted());
        await().pollInterval(Duration.ofMillis(50)).atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(eventsOf(failed)).hasSize(2));

        assertThat(events()).as("only finished runs are logged").hasSize(3);

        JsonNode ok = eventsOf(completed).get(0);
        List<String> names = new ArrayList<>();
        ok.fieldNames().forEachRemaining(names::add);
        // The ten original fields keep their order; the inference numbers (METRICS §2) and run facts follow them.
        assertThat(names).containsExactly("jobId", "attempt", "finishedAt", "uploadBytes", "uploadMs",
                "queuedMs", "processingMs", "totalMs", "workerType", "result",
                "inferMs", "gpuPeakMB", "modelParams", "outputVertices", "outputTriangles", "convertMs",
                "glbBytes", "passthrough", "modelVersion");
        assertThat(ok.path("attempt").asInt()).isEqualTo(1);
        assertThat(Instant.parse(ok.path("finishedAt").asText())).isBeforeOrEqualTo(Instant.now());
        assertThat(ok.path("uploadBytes").asLong()).isEqualTo(8);
        assertThat(ok.path("uploadMs").isIntegralNumber()).as("measured from request arrival").isTrue();
        assertThat(ok.path("uploadMs").asLong()).isGreaterThanOrEqualTo(0);
        assertThat(ok.path("queuedMs").asLong()).isGreaterThanOrEqualTo(80);
        assertThat(ok.path("processingMs").asLong()).isGreaterThanOrEqualTo(80);
        assertThat(ok.path("totalMs").asLong())
                .isGreaterThanOrEqualTo(ok.path("queuedMs").asLong() + ok.path("processingMs").asLong() - 1);
        assertThat(ok.path("workerType").asText()).isEqualTo("mock");
        assertThat(ok.path("result").asText()).isEqualTo("COMPLETED");
        for (String inference : List.of("inferMs", "gpuPeakMB", "modelParams", "outputVertices", "outputTriangles",
                "convertMs", "glbBytes", "passthrough", "modelVersion")) {
            assertThat(ok.get(inference)).as("%s is present (as null): the mock worker gets no /infer answer", inference).isNotNull();
            assertThat(ok.get(inference).isNull()).as(inference).isTrue();
        }

        List<JsonNode> failures = eventsOf(failed);
        assertThat(failures).extracting(e -> e.path("result").asText())
                .containsExactly("FAILED:INFERENCE_FAILED", "FAILED:INFERENCE_FAILED");
        assertThat(failures).extracting(e -> e.path("attempt").asInt()).containsExactly(1, 2);
        assertThat(failures).extracting(e -> e.path("uploadBytes").asLong()).containsExactly(4L, 4L);

        // The same runs as job_runs rows (docs/METRICS.md §1.1), built from the same object as the lines.
        await().pollInterval(Duration.ofMillis(50)).atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(jobRuns.count()).isEqualTo(3));
        JobRun completedRun = jobRuns.findById(completed + ":1").orElseThrow();
        assertThat(completedRun.getStatus()).isEqualTo(JobStatus.COMPLETED);
        assertThat(completedRun.getWorkerType()).isEqualTo("mock");
        assertThat(completedRun.getUploadBytes()).isEqualTo(8L);
        assertThat(completedRun.getUploadMs()).isEqualTo(ok.path("uploadMs").asLong());
        assertThat(completedRun.getTotalMs()).isEqualTo(ok.path("totalMs").asLong());
        assertThat(completedRun.getInferMs()).isNull();
        assertThat(completedRun.getPassthrough()).isNull();
        assertThat(jobRuns.findByJobIdOrderByAttemptAsc(failed))
                .extracting(run -> run.getAttempt() + " " + run.result())
                .containsExactly("1 FAILED:INFERENCE_FAILED", "2 FAILED:INFERENCE_FAILED");
    }

    private String create(MockMultipartFile... files) throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart("/api/v1/jobs");
        for (MockMultipartFile file : files) {
            request = request.file(file);
        }
        byte[] body = mvc.perform(request).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsByteArray();
        return objectMapper.readTree(body).path("jobId").asText();
    }

    private void awaitStatus(String jobId, String expected) {
        await().pollInterval(Duration.ofMillis(50)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            byte[] body = mvc.perform(get("/api/v1/jobs/{id}", jobId)).andReturn().getResponse().getContentAsByteArray();
            assertThat(objectMapper.readTree(body).path("status").asText()).isEqualTo(expected);
        });
    }

    private List<JsonNode> events() throws IOException {
        Path file = storage.resolve("events").resolve("events.jsonl");
        List<JsonNode> events = new ArrayList<>();
        if (!Files.exists(file)) {
            return events;
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                events.add(objectMapper.readTree(line));
            }
        }
        return events;
    }

    private List<JsonNode> eventsOf(String jobId) throws IOException {
        return events().stream().filter(e -> e.path("jobId").asText().equals(jobId)).toList();
    }

    private static MockMultipartFile jpeg(String name, int bytes) {
        return new MockMultipartFile("photos", name, "image/jpeg", new byte[bytes]);
    }
}
