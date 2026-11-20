package com.example.mockbackend;

import com.example.mockbackend.service.InferenceHealthMonitor;
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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Profile "real" with the prod offline wait and a webhook: the /healthz poll carries the token, tokenOk=false (FD17),
 * a busyForMs over inference.stuck-alert-ms (FD13) and a loadError (FD18) reach Discord once each and once more when
 * they end, and jobs are dispatched or kept waiting exactly as before (the alerts do not touch the UP/LOADING/DOWN
 * readiness RealJobWorker reads).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("real")
class InferenceAlertIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TOKEN = "it-s3cret";
    private static final String HEALTHY = ",\"tokenOk\":true,\"busyForMs\":0";
    private static final AtomicReference<String> healthExtra = new AtomicReference<>(HEALTHY);
    private static final AtomicBoolean modelLoaded = new AtomicBoolean(true);
    private static final AtomicReference<String> tokenSeen = new AtomicReference<>();
    private static final AtomicInteger inferCalls = new AtomicInteger();
    private static final List<String> posted = new CopyOnWriteArrayList<>();

    @TempDir
    static Path storage;

    private static HttpServer inference;
    private static HttpServer webhook;
    private static ExecutorService handlers;
    private static Path glb;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("inference.base-url", () -> "http://127.0.0.1:" + inference.getAddress().getPort());
        registry.add("inference.token", () -> TOKEN);
        registry.add("inference.timeout-ms", () -> "5000");
        registry.add("inference.offline-wait-ms", () -> "2500");
        registry.add("inference.offline-poll-ms", () -> "100");
        registry.add("inference.health-poll-ms", () -> "600000"); // only the startup check and the test's own
        registry.add("inference.stuck-alert-ms", () -> "1000");
        registry.add("notify.webhook-url", () -> "http://127.0.0.1:" + webhook.getAddress().getPort() + "/hook");
        registry.add("notify.down-grace-ms", () -> "45000");
        registry.add("storage.upload.dir", () -> storage.resolve("uploads").toString());
        registry.add("storage.result.dir", () -> storage.resolve("results").toString());
    }

    @BeforeAll
    static void startFakes() throws IOException {
        glb = storage.resolve("gpu-out.glb");
        Files.write(glb, RealJobWorkerTest.minimalGlb());
        handlers = Executors.newCachedThreadPool();
        inference = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        inference.setExecutor(handlers);
        inference.createContext("/healthz", exchange -> {
            tokenSeen.set(exchange.getRequestHeaders().getFirst("X-Inference-Token"));
            respond(exchange, 200, "{\"status\":\"ok\",\"device\":\"cuda\",\"modelLoaded\":" + modelLoaded.get()
                    + ",\"passthrough\":false" + healthExtra.get() + "}");
        });
        inference.createContext("/infer", exchange -> {
            inferCalls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            respond(exchange, 200, JSON.createObjectNode().put("glbPath", glb.toString()).put("passthrough", false).toString());
        });
        inference.start();
        webhook = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        webhook.setExecutor(handlers);
        webhook.createContext("/hook", exchange -> {
            posted.add(JSON.readTree(exchange.getRequestBody().readAllBytes()).path("content").asText());
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        webhook.start();
    }

    @AfterAll
    static void stopFakes() {
        inference.stop(0);
        webhook.stop(0);
        handlers.shutdownNow();
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    InferenceHealthMonitor monitor;

    /** Each test starts from a healthy service the monitor has read, with no alert open and nothing posted. */
    @BeforeEach
    void healthy() {
        modelLoaded.set(true);
        healthExtra.set(HEALTHY);
        monitor.refresh();
        posted.clear();
    }

    /**
     * FD18: the service answers but its model failed to load. The job waits PENDING and ends INFERENCE_UNAVAILABLE
     * after inference.offline-wait-ms exactly as for a model still loading (/infer is never called), and the channel
     * hears the load error once and its clearing once.
     */
    @Test
    void aModelLoadErrorReachesTheWebhookOnceWhileJobsWaitAsBefore() throws Exception {
        int callsBefore = inferCalls.get();
        modelLoaded.set(false);
        healthExtra.set(HEALTHY + ",\"loadError\":\"FileNotFoundError: [Errno 2] No such file or directory: '<path>'\"");
        monitor.refresh();
        String waiting = upload();
        await().during(Duration.ofMillis(1000)).atMost(Duration.ofSeconds(2)).until(() -> statusOf(waiting).equals("PENDING"));
        await().atMost(Duration.ofSeconds(10)).until(() -> statusOf(waiting).equals("FAILED"));
        assertThat(errorCodeOf(waiting)).isEqualTo("INFERENCE_UNAVAILABLE");
        assertThat(inferCalls.get()).isEqualTo(callsBefore);
        assertThat(monitor.current().state()).isEqualTo(InferenceHealthMonitor.State.LOADING);

        modelLoaded.set(true);   // fixed and started again: loads, then is ready
        healthExtra.set(HEALTHY + ",\"loadError\":null");
        monitor.refresh();
        monitor.refresh();

        await().atMost(Duration.ofSeconds(5)).until(() -> loadAlerts().size() == 2);
        await().during(Duration.ofMillis(600)).atMost(Duration.ofSeconds(5)).until(() -> loadAlerts().size() == 2);
        assertThat(loadAlerts().get(0)).startsWith("🧩 GPU 추론 서버가 모델을 불러오지 못했어요 (FileNotFoundError)");
        assertThat(loadAlerts().get(1)).isEqualTo("🧩 GPU 추론 서버의 모델 불러오기 오류가 풀렸어요");
        assertThat(posted).noneMatch(text -> text.contains("<path>") || text.contains("Errno"));
    }

    @Test
    void tokenAndStuckAlertsReachTheWebhookOnceWhileJobsRunAsBefore() throws Exception {
        int callsBefore = inferCalls.get();
        healthExtra.set(",\"tokenOk\":false,\"busyForMs\":0");
        String first = upload();   // the worker's own fresh /healthz check sees UP and starts the job
        await().atMost(Duration.ofSeconds(10)).until(() -> statusOf(first).equals("COMPLETED"));
        assertThat(inferCalls.get()).isEqualTo(callsBefore + 1);
        assertThat(tokenSeen.get()).isEqualTo(TOKEN);
        monitor.refresh();
        monitor.refresh();

        healthExtra.set(",\"tokenOk\":false,\"busyForMs\":5000");
        monitor.refresh();
        monitor.refresh();
        String second = upload();
        await().atMost(Duration.ofSeconds(10)).until(() -> statusOf(second).equals("COMPLETED"));
        assertThat(inferCalls.get()).isEqualTo(callsBefore + 2);
        assertThat(monitor.current().ready()).isTrue();

        healthExtra.set(",\"tokenOk\":true,\"busyForMs\":0");
        monitor.refresh();
        monitor.refresh();

        await().atMost(Duration.ofSeconds(5)).until(() -> alerts().size() == 4);
        await().during(Duration.ofMillis(600)).atMost(Duration.ofSeconds(5)).until(() -> alerts().size() == 4);
        assertThat(alerts().get(0)).startsWith("🔑 GPU 추론 서버가 백엔드의 추론 토큰을 받지 않아요");
        assertThat(alerts().get(1)).startsWith("🐢 GPU 추론 서버의 작업 하나가 5초째 끝나지 않아요 (기준 1초)");
        assertThat(alerts().get(2)).isEqualTo("🔑 GPU 추론 서버가 추론 토큰을 다시 받아요");
        assertThat(alerts().get(3)).isEqualTo("🟢 GPU 추론 서버의 오래 걸리던 작업이 끝났어요");
        assertThat(posted).noneMatch(text -> text.contains(TOKEN));
        // The normal start (UNKNOWN -> UP) stays silent and the state never left UP: no other GPU message.
        assertThat(posted).filteredOn(text -> text.contains("GPU 추론 서버")).hasSize(4);
    }

    private static List<String> alerts() {
        return posted.stream().filter(text -> text.startsWith("🔑") || text.startsWith("🐢")
                || text.contains("오래 걸리던 작업")).toList();
    }

    /** The model-load alert and its clearing among the posted messages. */
    private static List<String> loadAlerts() {
        return posted.stream().filter(text -> text.startsWith("🧩")).toList();
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

    private String errorCodeOf(String jobId) throws Exception {
        String body = mvc.perform(get("/api/v1/jobs/{id}", jobId)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JSON.readTree(body).path("error").path("code").asText();
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
