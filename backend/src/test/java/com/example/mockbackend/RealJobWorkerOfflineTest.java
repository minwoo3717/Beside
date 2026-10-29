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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Profile "real" with inference.offline-wait-ms (prod): while the GPU PC's /healthz does not report a loaded model,
 * jobs wait as PENDING without calling /infer, start once it does, and fail INFERENCE_UNAVAILABLE only after the wait.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("real")
class RealJobWorkerOfflineTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicBoolean gpuUp = new AtomicBoolean(false);
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
            if (gpuUp.get()) {
                respond(exchange, 200, "{\"status\":\"ok\",\"device\":\"cuda\",\"modelLoaded\":true,\"passthrough\":false}");
            } else {
                respond(exchange, 503, "{\"detail\":\"model loading\"}");
            }
        });
        inference.createContext("/infer", exchange -> {
            inferCalls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
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

    private String upload() throws Exception {
        String body = mvc.perform(multipart("/api/v1/jobs")
                        .file(new MockMultipartFile("photos", "dog.png", "image/png", new byte[]{(byte) 0x89, 'P', 'N', 'G'})))
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
