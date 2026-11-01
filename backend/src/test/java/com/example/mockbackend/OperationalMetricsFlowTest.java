package com.example.mockbackend;

import com.example.mockbackend.service.JobQueueGauge;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Operational meters (docs/METRICS.md §5) through the real job flow of the mock profile: created / retried counted
 * after the dispatch (Idempotency-Key replays not), every finished run in beside.jobs.finished with its timers, and the
 * queue gauge following the jobs.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OperationalMetricsFlowTest {
    @TempDir
    static Path storage;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("storage.upload.dir", () -> storage.resolve("uploads").toString());
        registry.add("storage.result.sample", () -> storage.resolve("sample-dog.glb").toString());
        registry.add("mock.worker.pending-ms", () -> "400");
        registry.add("mock.worker.processing-ms", () -> "50");
        registry.add("metrics.queue.refresh-ms", () -> "3600000"); // only the test refreshes the gauge
    }

    @BeforeAll
    static void writeSample() throws IOException {
        Files.write(storage.resolve("sample-dog.glb"), new byte[2048]);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    MeterRegistry registry;

    @Autowired
    JobQueueGauge queueGauge;

    @Test
    void jobFlowIsCountedOnceAtEachStep() throws Exception {
        double created = counter("beside.jobs.created");
        double retried = counter("beside.jobs.retried");
        double completed = finished("COMPLETED", "none");
        double failed = finished("FAILED", "INFERENCE_FAILED");

        String ok = create("dog.jpg", "metrics-key");
        queueGauge.refresh();
        assertThat(queue("pending")).as("the job waits pending-ms before PROCESSING").isGreaterThanOrEqualTo(1.0);
        assertThat(create("dog.jpg", "metrics-key")).as("Idempotency-Key replay").isEqualTo(ok);
        String bad = create("fail-dog.jpg", null);
        assertThat(counter("beside.jobs.created") - created).as("a replay is not a new job").isEqualTo(2.0);

        awaitStatus(ok, "COMPLETED");
        awaitStatus(bad, "FAILED");
        // The event goes out right after the terminal save: the counter may trail the status by a moment.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(finished("COMPLETED", "none") - completed).isEqualTo(1.0);
            assertThat(finished("FAILED", "INFERENCE_FAILED") - failed).isEqualTo(1.0);
        });
        Timer processing = registry.get("beside.job.processing")
                .tags("worker", "mock", "result", "COMPLETED", "passthrough", "false").timer();
        assertThat(processing.count()).isGreaterThanOrEqualTo(1);
        assertThat(registry.get("beside.job.queued").tags("worker", "mock", "result", "COMPLETED").timer()
                .max(TimeUnit.MILLISECONDS)).as("queuedMs ≈ pending-ms").isGreaterThanOrEqualTo(300.0);

        mvc.perform(post("/api/v1/jobs/{id}/retry", bad)).andExpect(status().isAccepted());
        assertThat(counter("beside.jobs.retried") - retried).isEqualTo(1.0);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(finished("FAILED", "INFERENCE_FAILED") - failed).as("the retry is a second run").isEqualTo(2.0));

        queueGauge.refresh();
        assertThat(queue("pending")).isEqualTo(0.0);
        assertThat(queue("processing")).isEqualTo(0.0);
    }

    private String create(String filename, String idempotencyKey) throws Exception {
        var request = multipart("/api/v1/jobs").file(new MockMultipartFile("photos", filename, "image/jpeg", new byte[16]));
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
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

    private double counter(String name) {
        Counter counter = registry.find(name).counter();
        return counter == null ? 0 : counter.count();
    }

    private double finished(String result, String code) {
        Counter counter = registry.find("beside.jobs.finished")
                .tags("result", result, "code", code, "worker", "mock", "passthrough", "false").counter();
        return counter == null ? 0 : counter.count();
    }

    private double queue(String state) {
        return registry.get("beside.jobs.queue").tag("state", state).gauge().value();
    }
}
