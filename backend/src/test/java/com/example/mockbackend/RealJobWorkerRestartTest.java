package com.example.mockbackend;

import com.example.mockbackend.service.InferenceHealthMonitor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Failure drill FD16b end to end (profile real, offline wait on as in prod): the Pod was stopped while a run was in
 * flight and came back as a new process. The old request is never answered; once /healthz names the new instance the
 * worker gives it up (INFERENCE_UNAVAILABLE) and the next job takes the only slot, instead of both waiting out
 * inference.timeout-ms (600 s in prod, 30 s here).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("real")
@DirtiesContext   // its own settings: closed afterwards instead of kept in the test context cache
class RealJobWorkerRestartTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicReference<String> instance = new AtomicReference<>("aaaaaaaaaaaa");
    private static final AtomicInteger inferCalls = new AtomicInteger();
    /** The first /infer never answers (the process it reached is gone); released at the end. */
    private static final CountDownLatch lostProcess = new CountDownLatch(1);

    @TempDir
    static Path storage;

    private static HttpServer inference;
    private static ExecutorService handlers;
    private static Path glb;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("inference.base-url", () -> "http://127.0.0.1:" + inference.getAddress().getPort());
        registry.add("inference.timeout-ms", () -> "30000");
        registry.add("inference.offline-wait-ms", () -> "20000");
        registry.add("inference.offline-poll-ms", () -> "100");
        registry.add("inference.health-poll-ms", () -> "600000"); // the test refreshes the monitor itself
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
        inference.createContext("/healthz", exchange -> respond(exchange, 200,
                "{\"status\":\"ok\",\"device\":\"cuda\",\"modelLoaded\":true,\"passthrough\":false,\"instanceId\":\""
                        + instance.get() + "\"}"));
        inference.createContext("/infer", exchange -> {
            exchange.getRequestBody().readAllBytes();
            if (inferCalls.incrementAndGet() == 1) {
                try {
                    lostProcess.await(60, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                exchange.close();
                return;
            }
            respond(exchange, 200, JSON.createObjectNode().put("glbPath", glb.toString()).put("passthrough", false).toString());
        });
        inference.start();
    }

    @AfterAll
    static void stopFakeInferenceService() {
        lostProcess.countDown();
        inference.stop(0);
        handlers.shutdownNow();
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    InferenceHealthMonitor monitor;

    @Test
    void aRequestToAProcessThatRestartedIsGivenUpAndTheNextJobRuns() throws Exception {
        String lost = upload();
        await().atMost(Duration.ofSeconds(10)).until(() -> inferCalls.get() == 1);   // in flight to process aaa
        String next = upload();
        Thread.sleep(500);
        assertThat(statusOf(lost)).isEqualTo("PROCESSING");
        assertThat(statusOf(next)).as("waits for the only slot").isEqualTo("PENDING");

        instance.set("bbbbbbbbbbbb");   // the Pod came back as a new process
        long restarted = System.nanoTime();
        monitor.refresh();               // the scheduled check (every 15 s in prod)

        await().atMost(Duration.ofSeconds(10)).until(() -> statusOf(lost).equals("FAILED"));
        assertThat((System.nanoTime() - restarted) / 1_000_000).as("not the 30 s timeout").isLessThan(5_000);
        String body = mvc.perform(get("/api/v1/jobs/{id}", lost)).andReturn().getResponse().getContentAsString();
        assertThat(JSON.readTree(body).path("error").path("code").asText()).isEqualTo("INFERENCE_UNAVAILABLE");

        await().atMost(Duration.ofSeconds(10)).until(() -> statusOf(next).equals("COMPLETED"));
        assertThat(inferCalls.get()).isEqualTo(2);
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
