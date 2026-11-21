package com.example.mockbackend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Profile "real" with inference.offline-wait-ms (prod): while the GPU PC's /healthz does not report a loaded model,
 * jobs wait as PENDING without calling /infer, start once it does, and fail INFERENCE_UNAVAILABLE only after the wait,
 * which counts from dispatch, not from taking the inference slot. Such a job reports queue.reason INFERENCE_OFFLINE,
 * and canceling it ends the wait.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("real")
class RealJobWorkerOfflineTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicBoolean gpuUp = new AtomicBoolean(false);
    private static final AtomicInteger inferCalls = new AtomicInteger();
    private static final AtomicInteger healthChecks = new AtomicInteger();

    @TempDir
    static Path storage;

    private static HttpServer inference;
    private static ExecutorService handlers;
    private static Path glb;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("inference.base-url", () -> "http://127.0.0.1:" + inference.getAddress().getPort());
        registry.add("inference.timeout-ms", () -> "5000");
        registry.add("inference.offline-wait-ms", () -> "2500");
        registry.add("inference.offline-poll-ms", () -> "100");
        registry.add("inference.health-poll-ms", () -> "600000"); // only the worker's own checks during the test
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
        inference.createContext("/healthz", exchange -> {
            healthChecks.incrementAndGet();
            if (gpuUp.get()) {
                respond(exchange, 200, "{\"status\":\"ok\",\"device\":\"cuda\",\"modelLoaded\":true,\"passthrough\":false,\"modelVersion\":\"fake-animallift@1+rig\"}");
            } else {
                respond(exchange, 503, "{\"detail\":\"model loading\"}");
            }
        });
        inference.createContext("/infer", exchange -> {
            inferCalls.incrementAndGet();
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (request.contains("broken-dog")) {
                respond(exchange, 500, "{\"detail\":{\"code\":\"INFERENCE_FAILED\",\"message\":\"model error\"}}");
                return;
            }
            if (request.contains("token-dog")) { // the service refuses the token: the model never ran
                respond(exchange, 401, "{\"detail\":{\"code\":\"INFERENCE_UNAVAILABLE\",\"message\":\"bad token\"}}");
                return;
            }
            respond(exchange, 200, JSON.createObjectNode().put("glbPath", glb.toString()).put("passthrough", false).toString());
        });
        inference.start();
    }

    @AfterAll
    static void stopFakeInferenceService() {
        inference.stop(0);
        handlers.shutdownNow();
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    com.example.mockbackend.repository.JobRunRepository jobRuns;

    @BeforeEach
    void reset() {
        gpuUp.set(false);
        inferCalls.set(0);
    }

    @Test
    void waitsAsPendingWhileTheGpuIsOffAndStartsWhenItComesBack() throws Exception {
        String jobId = upload();
        Thread.sleep(600);
        assertThat(statusOf(jobId)).isEqualTo("PENDING");
        assertThat(inferCalls.get()).isZero();

        gpuUp.set(true);
        await().atMost(Duration.ofSeconds(10)).until(() -> statusOf(jobId).equals("COMPLETED"));
        assertThat(inferCalls.get()).isEqualTo(1);
    }

    @Test
    void failsUnavailableOnlyAfterTheWait() throws Exception {
        long started = System.nanoTime();
        String jobId = upload();
        await().atMost(Duration.ofSeconds(15)).until(() -> statusOf(jobId).equals("FAILED"));
        assertThat((System.nanoTime() - started) / 1_000_000).isGreaterThanOrEqualTo(2_000);
        String body = mvc.perform(get("/api/v1/jobs/{id}", jobId)).andReturn().getResponse().getContentAsString();
        assertThat(JSON.readTree(body).path("error").path("code").asText()).isEqualTo("INFERENCE_UNAVAILABLE");
        assertThat(inferCalls.get()).isZero();
    }

    /**
     * Two jobs, one inference slot (max-concurrency 1), GPU off: the wait counts from when each job was accepted, so
     * both end about offline-wait-ms (2.5 s) after it. Counted from taking the slot, the second job waited for the
     * first one's whole wait and then its own (about 5 s, N x the limit for N queued jobs).
     */
    @Test
    void queuedJobsCountTheWaitFromAcceptanceNotFromTheSlot() throws Exception {
        long started = System.nanoTime();
        String first = upload();
        String second = upload();
        await().atMost(Duration.ofSeconds(15))
                .until(() -> statusOf(first).equals("FAILED") && statusOf(second).equals("FAILED"));
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        assertThat(elapsedMs).isGreaterThanOrEqualTo(2_000).isLessThan(4_000);
        for (String jobId : new String[]{first, second}) {
            String body = mvc.perform(get("/api/v1/jobs/{id}", jobId)).andReturn().getResponse().getContentAsString();
            assertThat(JSON.readTree(body).path("error").path("code").asText()).isEqualTo("INFERENCE_UNAVAILABLE");
        }
        assertThat(inferCalls.get()).isZero();
    }

    @Test
    void adminOverviewShowsTheMonitorsGpuReading() throws Exception {
        String body = mvc.perform(get("/api/v1/admin/overview")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        var inference = JSON.readTree(body).path("inference");
        assertThat(inference.isObject()).isTrue();
        assertThat(inference.path("state").asText()).isIn("UP", "LOADING", "DOWN", "UNKNOWN");
        assertThat(inference.has("since")).isTrue();
        assertThat(inference.path("detail").asText()).doesNotContain("127.0.0.1");
    }

    @Test
    void cancelingAJobThatWaitsForTheGpuStopsTheWait() throws Exception {
        int before = healthChecks.get();
        String jobId = upload();
        // The worker holds the slot and re-reads /healthz every offline-poll-ms while the job stays PENDING.
        await().atMost(Duration.ofSeconds(5)).until(() -> healthChecks.get() > before);
        String waiting = mvc.perform(get("/api/v1/jobs/{id}", jobId)).andReturn().getResponse().getContentAsString();
        assertThat(JSON.readTree(waiting).path("status").asText()).isEqualTo("PENDING");
        assertThat(JSON.readTree(waiting).path("queue").path("ahead").asInt()).isZero();
        assertThat(JSON.readTree(waiting).path("queue").path("reason").asText()).isEqualTo("INFERENCE_OFFLINE");

        String canceled = mvc.perform(post("/api/v1/jobs/{id}/cancel", jobId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JSON.readTree(canceled).path("error").path("code").asText()).isEqualTo("CANCELED");

        // The wait ends at the next poll: no more /healthz checks for this job, and no /infer once the GPU is back.
        Thread.sleep(300);
        int checksAfterCancel = healthChecks.get();
        gpuUp.set(true);
        await().during(Duration.ofMillis(800)).atMost(Duration.ofSeconds(3)).pollInterval(Duration.ofMillis(50))
                .untilAsserted(() -> {
                    assertThat(healthChecks.get()).as("the wait loop stopped").isEqualTo(checksAfterCancel);
                    assertThat(inferCalls.get()).isZero();
                    assertThat(statusOf(jobId)).isEqualTo("FAILED");
                });
        String body = mvc.perform(get("/api/v1/jobs/{id}", jobId)).andReturn().getResponse().getContentAsString();
        assertThat(JSON.readTree(body).path("error").path("code").asText()).as("not INFERENCE_UNAVAILABLE").isEqualTo("CANCELED");
    }

    @Test
    void aFailedRunCountsAgainstTheModelTheServiceHasLoaded() throws Exception {
        gpuUp.set(true);
        String jobId = upload("broken-dog.png");
        await().atMost(Duration.ofSeconds(10)).until(() -> statusOf(jobId).equals("FAILED"));
        await().atMost(Duration.ofSeconds(5)).until(() -> !jobRuns.findByJobIdOrderByAttemptAsc(jobId).isEmpty());
        var run = jobRuns.findByJobIdOrderByAttemptAsc(jobId).get(0);
        assertThat(run.getErrorCode()).isEqualTo("INFERENCE_FAILED");
        // The fake /healthz still says +rig, as the review branch's service did: +rig belongs only to a run whose rigging
        // succeeded (REVIEW_CHECKLIST #18), so the failure counts against the model without it.
        assertThat(run.getModelVersion()).as("admin stats group the failure with that model").isEqualTo("fake-animallift@1");
    }

    @Test
    void aRunTheModelNeverSawCountsAgainstNoModel() throws Exception {
        gpuUp.set(true); // /healthz is open without a token and reports the loaded model
        String jobId = upload("token-dog.png");
        await().atMost(Duration.ofSeconds(10)).until(() -> statusOf(jobId).equals("FAILED"));
        await().atMost(Duration.ofSeconds(5)).until(() -> !jobRuns.findByJobIdOrderByAttemptAsc(jobId).isEmpty());
        var run = jobRuns.findByJobIdOrderByAttemptAsc(jobId).get(0);
        assertThat(run.getErrorCode()).isEqualTo("INFERENCE_UNAVAILABLE");
        assertThat(run.getModelVersion()).as("a refused token is not a failure of the loaded model").isNull();
    }

    private String upload() throws Exception {
        return upload("dog.png");
    }

    private String upload(String filename) throws Exception {
        String body = mvc.perform(multipart("/api/v1/jobs")
                        .file(new MockMultipartFile("photos", filename, "image/png", new byte[]{(byte) 0x89, 'P', 'N', 'G'})))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(body).path("jobId").asText();
    }

    private String statusOf(String jobId) throws Exception {
        String body = mvc.perform(get("/api/v1/jobs/{id}", jobId)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JSON.readTree(body).path("status").asText();
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
