package com.example.mockbackend;

import com.example.mockbackend.domain.Job;
import com.example.mockbackend.domain.JobRun;
import com.example.mockbackend.repository.JobRunRepository;
import com.example.mockbackend.service.InferenceHealthMonitor;
import com.example.mockbackend.service.InferenceHealthMonitor.State;
import com.example.mockbackend.service.InferenceHealthMonitor.Status;
import com.example.mockbackend.service.preprocess.CutoutStage;
import com.example.mockbackend.service.preprocess.PreparedPhoto;
import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.boot.test.mock.mockito.MockBean;
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
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Profile "real": an unexpected error before the run reaches PROCESSING (the cutout stage before the inference slot,
 * the inference-service check after it) ends the job FAILED with INTERNAL_ERROR through JobFinisher, measured like
 * any other run, instead of leaving it PENDING until InterruptedJobRecovery at the next restart. The inference slot
 * (max-concurrency 1) is free again, so the next job runs.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("real")
class RealJobWorkerUnexpectedErrorTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Status UP = new Status(State.UP, "cuda", Instant.now(), Instant.now(), "ok");
    private static final AtomicBoolean healthCheckThrows = new AtomicBoolean();
    private static final AtomicBoolean cutoutThrows = new AtomicBoolean();
    private static final AtomicInteger inferCalls = new AtomicInteger();

    @TempDir
    static Path storage;

    private static HttpServer inference;
    private static ExecutorService handlers;
    private static Path glb;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("inference.base-url", () -> "http://127.0.0.1:" + inference.getAddress().getPort());
        registry.add("inference.timeout-ms", () -> "5000");
        registry.add("inference.max-concurrency", () -> "1");
        // Above 0 (prod): the worker asks the health monitor before every run, after taking the slot.
        registry.add("inference.offline-wait-ms", () -> "2000");
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
        inference.createContext("/infer", exchange -> {
            inferCalls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            respond(exchange, JSON.createObjectNode().put("glbPath", glb.toString()).put("passthrough", false).toString());
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
    JobRunRepository jobRuns;

    @MockBean
    InferenceHealthMonitor healthMonitor;

    @MockBean
    CutoutStage cutoutStage;

    @BeforeEach
    void fakes() throws InterruptedException {
        healthCheckThrows.set(false);
        cutoutThrows.set(false);
        inferCalls.set(0);
        when(healthMonitor.current()).thenReturn(UP);
        // A flag rather than a call order: the scheduler may call refresh() too.
        when(healthMonitor.refresh()).thenAnswer(call -> {
            if (healthCheckThrows.get()) {
                throw new IllegalStateException("a StateChanged listener failed");
            }
            return UP;
        });
        when(cutoutStage.prepare(any(Job.class))).thenAnswer(call -> {
            if (cutoutThrows.get()) {
                throw new IllegalStateException("cutout stage bug");
            }
            return PreparedPhoto.off();
        });
    }

    @Test
    void anErrorInTheInferenceServiceCheckFailsTheJobInsteadOfLeavingItPending() throws Exception {
        healthCheckThrows.set(true);
        String broken = upload();
        assertFailedWithInternalError(broken);

        healthCheckThrows.set(false);
        assertTheNextJobRuns();
    }

    @Test
    void anErrorBeforeTheInferenceSlotFailsTheJobInsteadOfLeavingItPending() throws Exception {
        cutoutThrows.set(true);
        String broken = upload();
        assertFailedWithInternalError(broken);

        cutoutThrows.set(false);
        assertTheNextJobRuns();
    }

    private void assertFailedWithInternalError(String jobId) throws Exception {
        await().atMost(Duration.ofSeconds(10)).until(() -> statusOf(jobId).equals("FAILED"));
        JsonNode job = job(jobId);
        assertThat(job.path("error").path("code").asText()).isEqualTo("INTERNAL_ERROR");
        assertThat(inferCalls.get()).isZero();
        // Ended through JobFinisher: one job_runs row like any other run.
        List<JobRun> runs = jobRuns.findByJobIdOrderByAttemptAsc(jobId);
        assertThat(runs).hasSize(1);
        assertThat(runs.get(0).result()).isEqualTo("FAILED:INTERNAL_ERROR");
    }

    /** One inference slot: the next job completes only if the failed run gave its slot back (or never took it). */
    private void assertTheNextJobRuns() throws Exception {
        String next = upload();
        await().atMost(Duration.ofSeconds(10)).until(() -> statusOf(next).equals("COMPLETED"));
        assertThat(inferCalls.get()).isEqualTo(1);
    }

    private String upload() throws Exception {
        String body = mvc.perform(multipart("/api/v1/jobs")
                        .file(new MockMultipartFile("photos", "dog.png", "image/png", new byte[]{(byte) 0x89, 'P', 'N', 'G'})))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(body).path("jobId").asText();
    }

    private JsonNode job(String jobId) throws Exception {
        String body = mvc.perform(get("/api/v1/jobs/{id}", jobId)).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return JSON.readTree(body);
    }

    private String statusOf(String jobId) throws Exception {
        return job(jobId).path("status").asText();
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
