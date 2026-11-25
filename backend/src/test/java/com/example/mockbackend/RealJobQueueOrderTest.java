package com.example.mockbackend;

import com.example.mockbackend.domain.Job;
import com.example.mockbackend.service.InferenceHealthMonitor;
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
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The GPU line keeps the accepted-or-retried order of JobQueue even when a retry reuses its cutout (review finding
 * 2026-11-25): a new job B whose cutout is still running and a retry R accepted after it, whose cutout is reused at
 * once, wait for one slot. B goes first. The place in the line used to be taken only after the cutout, so R ran before
 * B while B's queue.ahead said it was next. The cutout is a mock here: B's blocks until the test lets it go.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("real")
class RealJobQueueOrderTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    static Path storage;

    private static HttpServer inference;
    private static ExecutorService handlers;
    private static Path glb;
    private static final List<String> inferJobIds = new CopyOnWriteArrayList<>();
    private static volatile CountDownLatch gate = new CountDownLatch(0);

    /** The next cutout to start blocks on {@link #cutoutGate}; {@link #cutoutEntered} says it has started. */
    private final AtomicBoolean blockNextCutout = new AtomicBoolean();
    private final CountDownLatch cutoutEntered = new CountDownLatch(1);
    private final CountDownLatch cutoutGate = new CountDownLatch(1);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("inference.base-url", () -> "http://127.0.0.1:" + inference.getAddress().getPort());
        registry.add("inference.timeout-ms", () -> "20000");
        registry.add("inference.max-concurrency", () -> "1");
        registry.add("inference.slot-recheck-ms", () -> "100");
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
                respond(exchange, 200, JSON.createObjectNode().put("glbPath", glb.toString()).put("passthrough", false)
                        .put("modelVersion", "fake-order-0").toString());
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
    InferenceHealthMonitor healthMonitor;

    @MockBean
    CutoutStage cutoutStage;

    @BeforeEach
    void setUp() throws Exception {
        assertThat(healthMonitor.refresh().ready()).isTrue();
        when(cutoutStage.prepare(any(Job.class))).thenAnswer(invocation -> {
            if (blockNextCutout.compareAndSet(true, false)) {
                cutoutEntered.countDown();
                cutoutGate.await(15, TimeUnit.SECONDS);
            }
            return PreparedPhoto.off();
        });
    }

    @Test
    void aRetryWhoseCutoutIsReusedWaitsBehindANewJobAcceptedBeforeItThatIsStillInItsCutout() throws Exception {
        gate = new CountDownLatch(1);
        String running = create();
        awaitStatus(running, "PROCESSING");
        // R: a job that failed (canceled while it waited), to be retried later
        String retried = create();
        mvc.perform(post("/api/v1/jobs/{id}/cancel", retried)).andExpect(status().isOk());
        awaitStatus(retried, "FAILED");

        // B: a new job whose cutout is still running when R is retried
        blockNextCutout.set(true);
        String newJob = create();
        assertThat(cutoutEntered.await(5, TimeUnit.SECONDS)).as("B's cutout started").isTrue();
        mvc.perform(post("/api/v1/jobs/{id}/retry", retried)).andExpect(status().isAccepted());
        assertThat(readJob(newJob).path("queue").path("ahead").asInt()).as("B is next").isEqualTo(1);
        assertThat(readJob(retried).path("queue").path("ahead").asInt()).as("R is behind B").isEqualTo(2);

        // the running job ends and the slot is free, but B (first in line) is still in its cutout: R keeps waiting
        gate.countDown();
        awaitStatus(running, "COMPLETED");
        await().during(Duration.ofMillis(600)).atMost(Duration.ofSeconds(2))
                .until(() -> !inferJobIds.contains(retried));

        cutoutGate.countDown();
        awaitStatus(newJob, "COMPLETED");
        awaitStatus(retried, "COMPLETED");
        assertThat(inferJobIds).containsExactly(running, newJob, retried);
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
