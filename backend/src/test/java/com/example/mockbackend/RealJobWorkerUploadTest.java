package com.example.mockbackend;

import com.example.mockbackend.domain.JobRun;
import com.example.mockbackend.domain.JobStatus;
import com.example.mockbackend.repository.JobRunRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Profile "real" with inference.transfer=upload (CONTRACT §6.1, Spring on a VM and the inference service on the GPU
 * PC without a shared disk): the photos travel in POST /infer/upload, the GLB comes back through GET glbUrl, and both
 * sides are cleaned up afterwards. Its own class because the transfer mode is per Spring context; the path mode flow
 * and the failure table are in RealJobWorkerTest and InferenceClientTest.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("real")
class RealJobWorkerUploadTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TOKEN = "test-token-7f3a";
    private static final byte[] GLB = RealJobWorkerTest.minimalGlb();
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G'};

    @TempDir
    static Path storage;

    private static HttpServer inference;
    private static ExecutorService handlers;
    private static final List<List<MultipartPart>> uploads = new CopyOnWriteArrayList<>();
    /** "METHOD path-or-jobId token" for every request the fake service got. */
    private static final List<String> calls = new CopyOnWriteArrayList<>();
    private static volatile int artifactStatus;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("inference.base-url", () -> "http://127.0.0.1:" + inference.getAddress().getPort());
        registry.add("inference.transfer", () -> "upload");
        registry.add("inference.token", () -> TOKEN);
        registry.add("inference.timeout-ms", () -> "5000");
        registry.add("storage.upload.dir", () -> storage.resolve("uploads").toString());
        registry.add("storage.result.dir", () -> storage.resolve("results").toString());
        registry.add("storage.tmp.dir", () -> storage.resolve("tmp").toString());
        registry.add("storage.events.path", () -> storage.resolve("events.jsonl").toString());
    }

    @BeforeAll
    static void startFakeInferenceService() throws IOException {
        inference = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        handlers = Executors.newCachedThreadPool();
        inference.setExecutor(handlers);
        inference.createContext("/infer/upload", exchange -> {
            try {
                List<MultipartPart> parts = MultipartPart.parse(exchange.getRequestHeaders().getFirst("Content-Type"),
                        exchange.getRequestBody().readAllBytes());
                uploads.add(parts);
                String jobId = JSON.readTree(parts.get(0).bytes()).path("jobId").asText();
                calls.add("POST /infer/upload " + jobId + " " + tokenOf(exchange));
                if (!TOKEN.equals(tokenOf(exchange))) {
                    respond(exchange, 401, unauthorized());
                    return;
                }
                ObjectNode ok = JSON.createObjectNode();
                ok.put("glbPath", "/gpu/work/" + jobId + "/base.glb"); // the GPU PC's own path: Spring must not use it
                ok.put("glbUrl", "/artifacts/" + jobId + "/base.glb");
                ok.putObject("metrics").put("inferMs", 7).put("gpuPeakMB", 512).put("outputTriangles", 12).put("convertMs", 3);
                ok.put("passthrough", false);
                ok.put("modelVersion", "fake-animallift-0");
                respond(exchange, 200, ok.toString().getBytes(StandardCharsets.UTF_8));
            } finally {
                exchange.close();
            }
        });
        inference.createContext("/artifacts/", exchange -> {
            try {
                String method = exchange.getRequestMethod();
                calls.add(method + " " + exchange.getRequestURI().getPath() + " " + tokenOf(exchange));
                if (!TOKEN.equals(tokenOf(exchange))) {
                    respond(exchange, 401, unauthorized());
                } else if (method.equals("DELETE")) {
                    exchange.sendResponseHeaders(204, -1);
                } else if (artifactStatus != 200) {
                    respond(exchange, artifactStatus, "{\"detail\":\"artifact not found\"}".getBytes(StandardCharsets.UTF_8));
                } else {
                    exchange.getResponseHeaders().add("Content-Type", "model/gltf-binary");
                    respond(exchange, 200, GLB);
                }
            } finally {
                exchange.close();
            }
        });
        inference.start();
    }

    @AfterAll
    static void stopFakeInferenceService() {
        inference.stop(0);
        handlers.shutdownNow();
    }

    @BeforeEach
    void reset() {
        artifactStatus = 200;
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JobRunRepository jobRuns;

    @Test
    void uploadsThePhotosDownloadsTheGlbAndCleansUpBothSides() throws Exception {
        mvc.perform(get("/api/v1/healthz")).andExpect(jsonPath("$.workerType").value("real"));

        String jobId = create("dog.png");
        awaitStatus(jobId, "COMPLETED");

        JsonNode completed = readJob(jobId);
        assertThat(completed.path("asset").path("bytes").asLong()).isEqualTo(GLB.length);
        mvc.perform(get(completed.path("asset").path("url").asText()))
                .andExpect(status().isOk())
                .andExpect(content().contentType("model/gltf-binary"))
                .andExpect(content().bytes(GLB));
        assertThat(storage.resolve("results").resolve(jobId).resolve("base.glb")).hasBinaryContent(GLB);

        // What went over the wire: the request part, then the photo file under its original name.
        List<MultipartPart> parts = uploadOf(jobId);
        assertThat(parts).extracting(MultipartPart::name).containsExactly("request", "photos");
        JsonNode request = JSON.readTree(parts.get(0).bytes());
        assertThat(request.path("jobId").asText()).isEqualTo(jobId);
        assertThat(request.path("options").path("hair").asBoolean(true)).isFalse();
        assertThat(request.has("imagePaths")).as("no server paths in upload mode").isFalse();
        assertThat(parts.get(1).filename()).isEqualTo("dog.png");
        assertThat(parts.get(1).contentType()).isEqualTo("image/png");
        assertThat(parts.get(1).bytes()).isEqualTo(PNG);

        // Upload, download, then DELETE of the service's job folder; the temporary GLB is gone.
        await().pollInterval(Duration.ofMillis(50)).atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(callsOf(jobId)).containsExactly(
                        "POST /infer/upload " + jobId + " " + TOKEN,
                        "GET /artifacts/" + jobId + "/base.glb " + TOKEN,
                        "DELETE /artifacts/" + jobId + " " + TOKEN));
        await().pollInterval(Duration.ofMillis(50)).atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(storage.resolve("tmp").resolve(jobId)).doesNotExist());

        JobRun run = awaitRun(jobId);
        assertThat(run.getStatus()).isEqualTo(JobStatus.COMPLETED);
        assertThat(run.getWorkerType()).isEqualTo("real");
        assertThat(run.getInferMs()).isEqualTo(7L);
        assertThat(run.getGlbBytes()).isEqualTo((long) GLB.length);
        assertThat(run.getPassthrough()).isFalse();
        assertThat(run.getModelVersion()).isEqualTo("fake-animallift-0");
    }

    @Test
    void glbThatCannotBeDownloadedFailsTheRunAndStillCleansUp() throws Exception {
        artifactStatus = 404;

        String jobId = create("dog.png");
        awaitStatus(jobId, "FAILED");

        JsonNode failed = readJob(jobId);
        assertThat(failed.path("error").path("code").asText()).isEqualTo("CONVERSION_FAILED");
        assertThat(failed.path("error").path("message").asText()).isNotBlank()
                .doesNotContain(storage.toString()).doesNotContain("127.0.0.1").doesNotContain(TOKEN);
        mvc.perform(get("/api/v1/jobs/{id}/asset", jobId)).andExpect(status().isConflict());

        await().pollInterval(Duration.ofMillis(50)).atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(callsOf(jobId)).last().isEqualTo("DELETE /artifacts/" + jobId + " " + TOKEN));
        assertThat(storage.resolve("tmp").resolve(jobId)).doesNotExist();
        assertThat(awaitRun(jobId).result()).isEqualTo("FAILED:CONVERSION_FAILED");
    }

    private String create(String filename) throws Exception {
        MvcResult created = mvc.perform(multipart("/api/v1/jobs").file(new MockMultipartFile("photos", filename, "image/png", PNG)))
                .andExpect(status().isAccepted())
                .andReturn();
        return JSON.readTree(created.getResponse().getContentAsByteArray()).path("jobId").asText();
    }

    private void awaitStatus(String jobId, String expected) {
        await().pollInterval(Duration.ofMillis(50)).atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(readJob(jobId).path("status").asText()).isEqualTo(expected));
    }

    private JsonNode readJob(String jobId) throws Exception {
        return JSON.readTree(mvc.perform(get("/api/v1/jobs/{id}", jobId)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray());
    }

    private JobRun awaitRun(String jobId) {
        String id = jobId + ":1";
        await().pollInterval(Duration.ofMillis(50)).atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(jobRuns.findById(id)).isPresent());
        return jobRuns.findById(id).orElseThrow();
    }

    private static List<MultipartPart> uploadOf(String jobId) {
        return uploads.stream().filter(parts -> parts.get(0).text().contains(jobId)).reduce((a, b) -> b).orElseThrow();
    }

    private static List<String> callsOf(String jobId) {
        return calls.stream().filter(call -> call.contains(jobId)).toList();
    }

    private static String tokenOf(HttpExchange exchange) {
        String token = exchange.getRequestHeaders().getFirst("X-Inference-Token");
        return token == null ? "(none)" : token;
    }

    private static byte[] unauthorized() {
        return "{\"detail\":{\"code\":\"INFERENCE_UNAVAILABLE\",\"message\":\"missing or wrong X-Inference-Token\"}}"
                .getBytes(StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange exchange, int status, byte[] bytes) throws IOException {
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
    }
}
