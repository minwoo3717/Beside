package com.example.mockbackend;

import com.example.mockbackend.domain.JobRun;
import com.example.mockbackend.domain.JobStatus;
import com.example.mockbackend.repository.JobRunRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Profile "real" end to end against a fake inference service (docs/PLAN.md stage 4): upload → PENDING →
 * PROCESSING → POST /infer → COMPLETED with the returned GLB behind the asset endpoint, the four job failure codes,
 * retry, one inference at a time, and the per-run measurements (job_runs row + events.jsonl line, docs/METRICS.md §1)
 * carrying what /infer reported. Stage 4 proper repeats the flow with the AnimalLift service.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("real")
class RealJobWorkerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long TIMEOUT_MS = 1_000;
    /** Smallest valid glTF 2.0 binary: 12-byte header and one JSON chunk {"asset":{"version":"2.0"}}. */
    private static final byte[] GLB = minimalGlb();

    @TempDir
    static Path storage;

    private static HttpServer inference;
    private static ExecutorService handlers;
    private static final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private static final AtomicInteger inFlight = new AtomicInteger();
    private static final AtomicInteger maxInFlight = new AtomicInteger();
    private static volatile int answerStatus;
    private static volatile String answerBody;
    private static volatile long delayMs;
    private static volatile boolean drop;
    private static volatile CountDownLatch gate = new CountDownLatch(0);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("inference.base-url", () -> "http://127.0.0.1:" + inference.getAddress().getPort());
        registry.add("inference.timeout-ms", () -> String.valueOf(TIMEOUT_MS));
        registry.add("inference.max-concurrency", () -> "1");
        registry.add("storage.upload.dir", () -> storage.resolve("uploads").toString());
        registry.add("storage.result.dir", () -> storage.resolve("results").toString());
        registry.add("storage.events.path", () -> storage.resolve("events.jsonl").toString());
    }

    @BeforeAll
    static void startFakeInferenceService() throws IOException {
        inference = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        handlers = Executors.newCachedThreadPool(); // concurrent handlers: only Spring may serialize the requests
        inference.setExecutor(handlers);
        inference.createContext("/infer", exchange -> {
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            boolean counted = true;
            try {
                requests.add(JSON.readTree(exchange.getRequestBody()));
                gate.await(10, TimeUnit.SECONDS);
                Thread.sleep(delayMs);
                inFlight.decrementAndGet(); // before answering: the next request may arrive as soon as this one is read
                counted = false;
                if (drop) {
                    return; // close without an answer, as a crashed service would
                }
                byte[] bytes = answerBody.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(answerStatus, bytes.length == 0 ? -1 : bytes.length);
                if (bytes.length > 0) {
                    exchange.getResponseBody().write(bytes);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                if (counted) {
                    inFlight.decrementAndGet();
                }
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
    void resetFakeInferenceService() {
        // A request that timed out in an earlier test may still be sleeping in the fake service.
        await().atMost(Duration.ofSeconds(10)).until(() -> inFlight.get() == 0);
        maxInFlight.set(0);
        delayMs = 0;
        drop = false;
        gate = new CountDownLatch(0);
        answer(500, "fake service answer not set");
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JobRunRepository jobRuns;

    @Test
    void processingCallsInferAndServesTheReturnedGlb() throws Exception {
        mvc.perform(get("/api/v1/healthz")).andExpect(jsonPath("$.workerType").value("real"));
        gate = new CountDownLatch(1);
        answerOk(writeGlb("base-ok.glb"));

        String jobId = create("dog.png");
        awaitStatus(jobId, "PROCESSING");
        JsonNode processing = readJob(jobId);
        assertThat(processing.path("progress").isNull()).as("the inference service reports no progress").isTrue();
        assertThat(processing.path("timings").path("processingMs").isNumber()).isTrue();
        gate.countDown();

        awaitStatus(jobId, "COMPLETED");
        JsonNode completed = readJob(jobId);
        assertThat(completed.path("progress").asDouble()).isEqualTo(1.0);
        assertThat(completed.path("asset").path("bytes").asLong()).isEqualTo(GLB.length);
        mvc.perform(get(completed.path("asset").path("url").asText()))
                .andExpect(status().isOk())
                .andExpect(content().contentType("model/gltf-binary"))
                .andExpect(content().bytes(GLB));
        assertThat(storage.resolve("results").resolve(jobId).resolve("base.glb")).hasBinaryContent(GLB);

        JsonNode sent = requestOf(jobId);
        assertThat(sent.path("imagePaths")).hasSize(1);
        Path image = Path.of(sent.path("imagePaths").get(0).asText());
        assertThat(image).isAbsolute().exists();
        assertThat(image.getFileName().toString()).endsWith("dog.png");
        assertThat(sent.path("options").path("hair").asBoolean(true)).isFalse();

        // The run's measurements: the job_runs row and the events.jsonl line carry what /infer reported (METRICS §1, §2).
        JobRun run = awaitRun(jobId, 1);
        assertThat(run.getStatus()).isEqualTo(JobStatus.COMPLETED);
        assertThat(run.getWorkerType()).isEqualTo("real");
        assertThat(run.getUploadBytes()).isEqualTo(4L);
        assertThat(run.getUploadMs()).isNotNull();
        assertThat(run.getProcessingMs()).isNotNull();
        assertThat(run.getInferMs()).isEqualTo(7L);
        assertThat(run.getGpuPeakMB()).isEqualTo(512L);
        assertThat(run.getModelParams()).isEqualTo(312_000_000L);
        assertThat(run.getOutputVertices()).isEqualTo(10L);
        assertThat(run.getOutputTriangles()).isEqualTo(12L);
        assertThat(run.getConvertMs()).isEqualTo(3L);
        assertThat(run.getGlbBytes()).isEqualTo((long) GLB.length);
        assertThat(run.getPassthrough()).isFalse();
        assertThat(run.getModelVersion()).isEqualTo("fake-animallift-0");

        assertThat(awaitEvents(jobId, 1)).singleElement().satisfies(event -> {
            assertThat(event.path("workerType").asText()).isEqualTo("real");
            assertThat(event.path("result").asText()).isEqualTo("COMPLETED");
            assertThat(event.path("inferMs").asLong()).isEqualTo(7L);
            assertThat(event.path("glbBytes").asLong()).isEqualTo(GLB.length);
            assertThat(event.path("passthrough").asBoolean()).isFalse();
            assertThat(event.path("modelVersion").asText()).isEqualTo("fake-animallift-0");
            assertThat(event.path("totalMs").asLong()).as("line and row come from one object").isEqualTo(run.getTotalMs());
        });
    }

    @Test
    void passthroughRunIsFlaggedSoMeasurementsCanExcludeIt() throws Exception {
        answerOk(writeGlb("base-passthrough.glb"), true);

        String jobId = create("dog.png");
        awaitStatus(jobId, "COMPLETED");

        JobRun run = awaitRun(jobId, 1);
        assertThat(run.getPassthrough()).as("job_runs.passthrough: the sample came back, not a model result").isTrue();
        assertThat(run.getModelVersion()).isNull();
        assertThat(run.getGlbBytes()).isEqualTo((long) GLB.length);
        assertThat(awaitEvents(jobId, 1).get(0).path("passthrough").asBoolean()).isTrue();
    }

    @Test
    void everyFailurePathEndsFailedWithItsJobFailureCode() throws Exception {
        answer(400, detail("INFERENCE_FAILED", "no dog found in dog.png"));
        assertFailsWith(create("dog.png"), "INFERENCE_FAILED");

        answerOk(writeGlb("base-slow.glb"));
        delayMs = TIMEOUT_MS * 3;
        assertFailsWith(create("dog.png"), "INFERENCE_TIMEOUT");
        delayMs = 0;

        drop = true;
        assertFailsWith(create("dog.png"), "INFERENCE_UNAVAILABLE");
        drop = false;

        Path html = storage.resolve("error-page.glb");
        Files.writeString(html, "<html>not a model</html>");
        answerOk(html);
        String notGlb = create("dog.png");
        assertFailsWith(notGlb, "CONVERSION_FAILED");
        mvc.perform(get("/api/v1/jobs/{id}/asset", notGlb))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("JOB_NOT_COMPLETED"));

        Path empty = storage.resolve("empty.glb"); // like the 0-byte sample-dog.glb
        Files.write(empty, new byte[0]);
        answerOk(empty);
        assertFailsWith(create("dog.png"), "CONVERSION_FAILED");

        answerOk(storage.resolve("missing.glb"));
        assertFailsWith(create("dog.png"), "CONVERSION_FAILED");
    }

    @Test
    void failedRunCanBeRetriedAndCompletes() throws Exception {
        answer(501, detail("INFERENCE_UNAVAILABLE", "model not loaded yet"));
        String jobId = create("dog.png");
        assertFailsWith(jobId, "INFERENCE_UNAVAILABLE");

        answerOk(writeGlb("base-retry.glb"));
        mvc.perform(post("/api/v1/jobs/{id}/retry", jobId)).andExpect(status().isAccepted());
        awaitStatus(jobId, "COMPLETED");

        assertThat(readJob(jobId).path("error").isNull()).isTrue();
        assertThat(awaitEvents(jobId, 2))
                .extracting(event -> event.path("attempt").asInt() + " " + event.path("result").asText())
                .containsExactly("1 FAILED:INFERENCE_UNAVAILABLE", "2 COMPLETED");
        assertThat(requests.stream().filter(request -> request.path("jobId").asText().equals(jobId))).hasSize(2);

        // One job_runs row per attempt; only the completed one has inference numbers.
        assertThat(awaitRun(jobId, 2).getInferMs()).isEqualTo(7L);
        assertThat(jobRuns.findByJobIdOrderByAttemptAsc(jobId))
                .extracting(run -> run.getId() + " " + run.result())
                .containsExactly(jobId + ":1 FAILED:INFERENCE_UNAVAILABLE", jobId + ":2 COMPLETED");
    }

    @Test
    void runsOneInferenceAtATimeWhileOtherJobsWaitAsPending() throws Exception {
        answerOk(writeGlb("base-queue.glb"));
        delayMs = 200;

        List<String> jobIds = List.of(create("dog-1.png"), create("dog-2.png"), create("dog-3.png"));
        for (String jobId : jobIds) {
            awaitStatus(jobId, "COMPLETED");
        }

        assertThat(maxInFlight.get()).as("inference.max-concurrency=1: one /infer request at a time").isEqualTo(1);
        long longestQueue = 0;
        for (String jobId : jobIds) {
            longestQueue = Math.max(longestQueue, readJob(jobId).path("timings").path("queuedMs").asLong());
        }
        assertThat(longestQueue).as("the last job waited as PENDING for the two before it").isGreaterThanOrEqualTo(300);
    }

    private void assertFailsWith(String jobId, String code) throws Exception {
        awaitStatus(jobId, "FAILED");
        JsonNode failed = readJob(jobId);
        assertThat(failed.path("error").path("code").asText()).isEqualTo(code);
        assertThat(failed.path("error").path("message").asText()).isNotBlank()
                .as("responses never name server paths or internal hosts (CLAUDE.md)")
                .doesNotContain(storage.toString()).doesNotContain("127.0.0.1");
        assertThat(failed.path("progress").isNull()).isTrue();
        assertThat(awaitEvents(jobId, 1).get(0).path("result").asText()).isEqualTo("FAILED:" + code);

        JobRun run = awaitRun(jobId, 1);
        assertThat(run.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(run.getErrorCode()).isEqualTo(code);
        assertThat(run.result()).isEqualTo("FAILED:" + code);
        assertThat(run.getInferMs()).as("a failed run has no inference numbers").isNull();
        assertThat(run.getGlbBytes()).isNull();
        assertThat(run.getPassthrough()).isNull();
        assertThat(run.getModelVersion()).isNull();
    }

    /** JobFinisher writes the job_runs row right after the status save; wait for it like the events line. */
    private JobRun awaitRun(String jobId, int attempt) {
        String id = jobId + ":" + attempt;
        await().pollInterval(Duration.ofMillis(50)).atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(jobRuns.findById(id)).isPresent());
        return jobRuns.findById(id).orElseThrow();
    }

    private String create(String filename) throws Exception {
        MvcResult created = mvc.perform(multipart("/api/v1/jobs")
                        .file(new MockMultipartFile("photos", filename, "image/png", new byte[]{(byte) 0x89, 'P', 'N', 'G'})))
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

    /** JobFinisher saves the status first and then appends the line, so wait for the line. */
    private static List<JsonNode> awaitEvents(String jobId, int count) throws IOException {
        await().pollInterval(Duration.ofMillis(50)).atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(eventsOf(jobId)).hasSize(count));
        return eventsOf(jobId);
    }

    private static List<JsonNode> eventsOf(String jobId) throws IOException {
        Path events = storage.resolve("events.jsonl");
        List<JsonNode> lines = new ArrayList<>();
        if (!Files.exists(events)) {
            return lines;
        }
        for (String line : Files.readAllLines(events, StandardCharsets.UTF_8)) {
            JsonNode event = JSON.readTree(line);
            if (event.path("jobId").asText().equals(jobId)) {
                lines.add(event);
            }
        }
        return lines;
    }

    private static JsonNode requestOf(String jobId) {
        return requests.stream().filter(request -> request.path("jobId").asText().equals(jobId))
                .reduce((first, second) -> second).orElseThrow();
    }

    private static Path writeGlb(String name) throws IOException {
        Path file = storage.resolve(name);
        Files.write(file, GLB);
        return file;
    }

    private static void answerOk(Path glb) {
        answerOk(glb, false);
    }

    /** A full answer as the real service sends it: the METRICS §2 numbers and, for a model result, its version. */
    private static void answerOk(Path glb, boolean passthrough) {
        ObjectNode body = JSON.createObjectNode();
        body.put("glbPath", glb.toAbsolutePath().toString());
        body.putObject("metrics").put("inferMs", 7).put("gpuPeakMB", 512).put("modelParams", 312_000_000L)
                .put("outputVertices", 10).put("outputTriangles", 12).put("convertMs", 3);
        body.put("passthrough", passthrough);
        if (!passthrough) {
            body.put("modelVersion", "fake-animallift-0");
        }
        answer(200, body.toString());
    }

    private static void answer(int status, String body) {
        answerStatus = status;
        answerBody = body;
    }

    private static String detail(String code, String message) {
        ObjectNode body = JSON.createObjectNode();
        body.putObject("detail").put("code", code).put("message", message);
        return body.toString();
    }

    private static byte[] minimalGlb() {
        byte[] json = "{\"asset\":{\"version\":\"2.0\"}}".getBytes(StandardCharsets.US_ASCII);
        int chunkLength = (json.length + 3) & ~3; // chunks are 4-byte aligned; JSON is padded with spaces
        ByteBuffer glb = ByteBuffer.allocate(12 + 8 + chunkLength).order(ByteOrder.LITTLE_ENDIAN);
        glb.putInt(0x46546C67).putInt(2).putInt(12 + 8 + chunkLength); // "glTF", version 2, total length
        glb.putInt(chunkLength).putInt(0x4E4F534A).put(json);           // chunk length, "JSON", data
        while (glb.hasRemaining()) {
            glb.put((byte) ' ');
        }
        return glb.array();
    }
}
