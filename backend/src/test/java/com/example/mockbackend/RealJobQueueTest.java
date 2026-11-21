package com.example.mockbackend;

import com.example.mockbackend.domain.JobRun;
import com.example.mockbackend.repository.JobRunRepository;
import com.example.mockbackend.service.InferenceHealthMonitor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Profile "real" with one GPU slot (inference.max-concurrency=1) against a fake inference service that holds every
 * /infer request until the test opens its gate: the place of each waiting job (JobResponse.queue.ahead), the estimate
 * (JobResponse.estimate), absent until a run has completed, and canceling a job that waits for the slot, after which
 * /infer is never called for it. The test order matters: the first test needs an empty job_runs table.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("real")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RealJobQueueTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    /** Every /infer answer takes at least this long once the gate is open, so completed runs have a real processingMs. */
    private static final long INFER_MS = 150;

    @TempDir
    static Path storage;

    private static HttpServer inference;
    private static ExecutorService handlers;
    private static Path glb;
    private static final List<String> inferJobIds = new CopyOnWriteArrayList<>();
    private static volatile CountDownLatch gate = new CountDownLatch(0);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("inference.base-url", () -> "http://127.0.0.1:" + inference.getAddress().getPort());
        registry.add("inference.timeout-ms", () -> "20000");
        registry.add("inference.max-concurrency", () -> "1");
        registry.add("storage.upload.dir", () -> storage.resolve("uploads").toString());
        registry.add("storage.result.dir", () -> storage.resolve("results").toString());
    }

    @BeforeAll
    static void startFakeInferenceService() throws IOException {
        glb = storage.resolve("gpu-out.glb");
        Files.write(glb, RealJobWorkerTest.minimalGlb());
        inference = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        handlers = Executors.newCachedThreadPool();
        inference.setExecutor(handlers);
        inference.createContext("/healthz", exchange ->
                respond(exchange, 200, "{\"status\":\"ok\",\"device\":\"cuda\",\"modelLoaded\":true,\"passthrough\":false}"));
        inference.createContext("/infer", exchange -> {
            try {
                inferJobIds.add(JSON.readTree(exchange.getRequestBody()).path("jobId").asText());
                gate.await(15, TimeUnit.SECONDS);
                Thread.sleep(INFER_MS);
                respond(exchange, 200, JSON.createObjectNode().put("glbPath", glb.toString()).put("passthrough", false)
                        .put("modelVersion", "fake-queue-0").toString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        inference.start();
    }

    @AfterAll
    static void stopFakeInferenceService() {
        gate.countDown();
        inference.stop(0);
        handlers.shutdownNow();
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JobRunRepository jobRuns;

    @Autowired
    InferenceHealthMonitor healthMonitor;

    @BeforeEach
    void gpuIsUp() {
        // The scheduled check runs at startup; read it once more so queue.reason does not depend on that timing.
        assertThat(healthMonitor.refresh().ready()).isTrue();
    }

    @Test
    @Order(1)
    void waitingJobsSeeTheirPlaceAndAnEstimateOnceRunsHaveCompleted() throws Exception {
        // Round 1: nothing has completed yet, so there is a place in the queue but no estimate.
        gate = new CountDownLatch(1);
        String first = create();
        awaitStatus(first, "PROCESSING");
        String second = create();
        String third = create();

        JsonNode running = readJob(first);
        assertThat(running.has("queue")).isTrue();
        assertThat(running.path("queue").isNull()).as("queue only while PENDING").isTrue();
        assertThat(running.path("estimate").isNull()).as("no completed run to estimate from").isTrue();
        JsonNode waiting = readJob(second);
        assertThat(waiting.path("status").asText()).isEqualTo("PENDING");
        assertThat(waiting.path("queue").path("ahead").asInt()).as("behind the running job").isEqualTo(1);
        assertThat(waiting.path("queue").path("reason").isNull()).as("the GPU is up").isTrue();
        assertThat(waiting.path("estimate").isNull()).isTrue();
        assertThat(readJob(third).path("queue").path("ahead").asInt()).as("behind the running job and the second").isEqualTo(2);

        gate.countDown();
        for (String jobId : List.of(first, second, third)) {
            awaitStatus(jobId, "COMPLETED");
        }
        assertThat(readJob(third).path("queue").isNull()).isTrue();
        assertThat(readJob(third).path("estimate").isNull()).isTrue();
        awaitRun(third);

        // Round 2: three completed runs; their median processingMs (at least INFER_MS) is the estimate's unit.
        gate = new CountDownLatch(1);
        String busy = create();
        awaitStatus(busy, "PROCESSING");
        String next = create();
        String last = create();

        JsonNode busyEstimate = readJob(busy).path("estimate");
        JsonNode nextEstimate = readJob(next).path("estimate");
        JsonNode lastEstimate = readJob(last).path("estimate");
        assertThat(nextEstimate.path("basedOnRuns").asInt()).isEqualTo(3);
        assertThat(nextEstimate.path("remainingMs").asLong()).as("own run plus the rest of the running one")
                .isGreaterThanOrEqualTo(INFER_MS);
        assertThat(lastEstimate.path("remainingMs").asLong()).as("one more job ahead")
                .isGreaterThan(nextEstimate.path("remainingMs").asLong());
        assertThat(busyEstimate.path("basedOnRuns").asInt()).isEqualTo(3);
        assertThat(busyEstimate.path("remainingMs").asLong()).as("PROCESSING: median minus its own time, at least 0")
                .isGreaterThanOrEqualTo(0).isLessThan(nextEstimate.path("remainingMs").asLong());
        assertThat(readJob(next).path("queue").path("ahead").asInt()).isEqualTo(1);
        assertThat(readJob(last).path("queue").path("ahead").asInt()).isEqualTo(2);

        gate.countDown();
        for (String jobId : List.of(busy, next, last)) {
            awaitStatus(jobId, "COMPLETED");
        }
    }

    @Test
    @Order(2)
    void cancelingAJobThatWaitsForTheGpuTakesItOutOfTheQueue() throws Exception {
        gate = new CountDownLatch(1);
        String running = create();
        awaitStatus(running, "PROCESSING");
        String canceled = create();
        String after = create();
        assertThat(readJob(after).path("queue").path("ahead").asInt()).isEqualTo(2);

        // A started GPU run cannot be stopped.
        mvc.perform(post("/api/v1/jobs/{id}/cancel", running))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("JOB_NOT_PENDING"))
                .andExpect(jsonPath("$.error.jobId").value(running));

        JsonNode result = JSON.readTree(mvc.perform(post("/api/v1/jobs/{id}/cancel", canceled))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray());
        assertThat(result.path("status").asText()).isEqualTo("FAILED");
        assertThat(result.path("error").path("code").asText()).isEqualTo("CANCELED");
        assertThat(result.path("queue").isNull()).isTrue();
        assertThat(result.path("estimate").isNull()).isTrue();
        assertThat(readJob(after).path("queue").path("ahead").asInt()).as("the canceled job left the queue").isEqualTo(1);

        gate.countDown();
        awaitStatus(running, "COMPLETED");
        awaitStatus(after, "COMPLETED");
        // The canceled job's worker still takes its turn on the slot, finds the job FAILED and gives the slot back.
        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(3)).pollInterval(Duration.ofMillis(50))
                .untilAsserted(() -> {
                    assertThat(inferJobIds).contains(running, after).doesNotContain(canceled);
                    assertThat(readJob(canceled).path("error").path("code").asText()).isEqualTo("CANCELED");
                });
        assertThat(jobRuns.findByJobIdOrderByAttemptAsc(canceled)).extracting(JobRun::result)
                .containsExactly("FAILED:CANCELED");
    }

    private String create() throws Exception {
        MvcResult created = mvc.perform(multipart("/api/v1/jobs")
                        .file(new MockMultipartFile("photos", "dog.png", "image/png", new byte[]{(byte) 0x89, 'P', 'N', 'G'})))
                .andExpect(status().isAccepted())
                .andReturn();
        return JSON.readTree(created.getResponse().getContentAsByteArray()).path("jobId").asText();
    }

    private void awaitStatus(String jobId, String expected) {
        await().pollInterval(Duration.ofMillis(25)).atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(readJob(jobId).path("status").asText()).isEqualTo(expected));
    }

    /** JobFinisher writes the job_runs row right after the status save. */
    private void awaitRun(String jobId) {
        await().pollInterval(Duration.ofMillis(25)).atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(jobRuns.findByJobIdOrderByAttemptAsc(jobId)).isNotEmpty());
    }

    private JsonNode readJob(String jobId) throws Exception {
        return JSON.readTree(mvc.perform(get("/api/v1/jobs/{id}", jobId)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray());
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
