package com.example.mockbackend;

import com.example.mockbackend.domain.RunDetails;
import com.example.mockbackend.exception.ErrorCode;
import com.example.mockbackend.service.InferenceClient;
import com.example.mockbackend.service.InferenceFailure;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Spring side of the internal /infer contract (generation/inference_service/README.md) against a fake inference
 * service: the request it sends, and the job failure code (docs/api/ERROR_CODES.md) every kind of answer becomes.
 */
class InferenceClientTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dir;

    private HttpServer server;
    private ExecutorService handlers;
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private final List<String> contentTypes = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String body = "{}";
    private volatile long delayMs;
    private volatile boolean drop;

    @BeforeEach
    void startFakeInferenceService() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        handlers = Executors.newCachedThreadPool();
        server.setExecutor(handlers);
        server.createContext("/infer", exchange -> {
            try {
                requests.add(JSON.readTree(exchange.getRequestBody()));
                contentTypes.add(exchange.getRequestHeaders().getFirst("Content-Type"));
                Thread.sleep(delayMs);
                if (drop) {
                    return; // close without an answer, as a crashed service would
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
                if (bytes.length > 0) {
                    exchange.getResponseBody().write(bytes);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    @AfterEach
    void stopFakeInferenceService() {
        server.stop(0);
        handlers.shutdownNow();
    }

    @Test
    void sendsJobIdAbsoluteImagePathsAndNoHairThenReadsTheGlbPath() throws Exception {
        Path glb = dir.resolve("job-1").resolve("base.glb");
        ObjectNode ok = JSON.createObjectNode();
        ok.put("glbPath", glb.toString());
        ok.putObject("metrics").put("inferMs", 12).put("outputTriangles", 19876).put("gpuPeakMB", "n/a");
        ok.put("passthrough", true);
        ok.put("modelVersion", "animallift-paper");
        answer(200, ok.toString());

        InferenceClient.Result result = client(5_000).infer("job-1", List.of(Path.of("storage/uploads/job-1_dog.jpg")));

        assertThat(result.glbPath()).isEqualTo(glb);
        assertThat(result.passthrough()).isTrue();
        assertThat(result.metrics().path("inferMs").asInt()).isEqualTo(12);
        assertThat(result.modelVersion()).isEqualTo("animallift-paper");

        // The per-run measurements JobFinisher stores (job_runs, events.jsonl): numbers only, the rest is null.
        RunDetails details = result.details(1234L);
        assertThat(details.inferMs()).isEqualTo(12L);
        assertThat(details.outputTriangles()).isEqualTo(19876L);
        assertThat(details.gpuPeakMB()).as("a metric that is not a number is dropped, not an error").isNull();
        assertThat(details.modelParams()).as("absent metric").isNull();
        assertThat(details.glbBytes()).isEqualTo(1234L);
        assertThat(details.passthrough()).isTrue();
        assertThat(details.modelVersion()).isEqualTo("animallift-paper");

        assertThat(requests).hasSize(1);
        JsonNode sent = requests.get(0);
        assertThat(sent.path("jobId").asText()).isEqualTo("job-1");
        assertThat(sent.path("imagePaths")).hasSize(1);
        assertThat(Path.of(sent.path("imagePaths").get(0).asText()))
                .as("the service gets paths it can open, not paths relative to Spring's working directory")
                .isAbsolute()
                .isEqualTo(Path.of("storage/uploads/job-1_dog.jpg").toAbsolutePath().normalize());
        assertThat(sent.path("options").path("hair").isBoolean()).isTrue();
        assertThat(sent.path("options").path("hair").asBoolean()).isFalse();
        assertThat(contentTypes).containsExactly("application/json");
    }

    @Test
    void optionalAnswerFieldsDefaultToNullOrFalse() throws Exception {
        Path glb = dir.resolve("base.glb");
        ObjectNode onlyPath = JSON.createObjectNode();
        onlyPath.put("glbPath", glb.toString());
        answer(200, onlyPath.toString());

        InferenceClient.Result result = client(5_000).infer("job-6", List.of(dir.resolve("dog.jpg")));

        assertThat(result.glbPath()).isEqualTo(glb);
        assertThat(result.passthrough()).isFalse();
        assertThat(result.modelVersion()).isNull();
        assertThat(result.details(0L)).isEqualTo(new RunDetails(null, null, null, null, null, null, 0L, false, null));
    }

    @Test
    void blankOrOverlongModelVersionIsNormalized() throws Exception {
        ObjectNode blank = JSON.createObjectNode();
        blank.put("glbPath", dir.resolve("base.glb").toString());
        blank.put("modelVersion", "   ");
        answer(200, blank.toString());
        assertThat(client(5_000).infer("job-7", List.of(dir.resolve("dog.jpg"))).modelVersion()).isNull();

        ObjectNode overlong = JSON.createObjectNode();
        overlong.put("glbPath", dir.resolve("base.glb").toString());
        overlong.put("modelVersion", "v".repeat(200));
        answer(200, overlong.toString());
        assertThat(client(5_000).infer("job-8", List.of(dir.resolve("dog.jpg"))).modelVersion())
                .as("fits the job_runs.model_version column").hasSize(128);
    }

    static Stream<Arguments> answers() {
        return Stream.of(
                // A job failure code in detail.code wins (the error table of the /infer contract).
                Arguments.of(400, detail("INFERENCE_FAILED", "image not found: /data/dog1.jpg"), ErrorCode.INFERENCE_FAILED),
                Arguments.of(500, detail("CONVERSION_FAILED", "GLB_SPEC: 120000 triangles"), ErrorCode.CONVERSION_FAILED),
                Arguments.of(501, detail("INFERENCE_UNAVAILABLE", "model not loaded"), ErrorCode.INFERENCE_UNAVAILABLE),
                Arguments.of(500, detail("INFERENCE_TIMEOUT", "model gave up after 300 s"), ErrorCode.INFERENCE_TIMEOUT),
                // Without a known code the HTTP status decides.
                Arguments.of(500, "Internal Server Error", ErrorCode.INFERENCE_FAILED),
                Arguments.of(422, "{\"detail\":[{\"loc\":[\"body\",\"jobId\"],\"msg\":\"Field required\"}]}", ErrorCode.INFERENCE_FAILED),
                Arguments.of(400, detail("SOMETHING_NEW", "a code Spring does not know"), ErrorCode.INFERENCE_FAILED),
                Arguments.of(503, "", ErrorCode.INFERENCE_UNAVAILABLE),
                Arguments.of(502, "<html>Bad Gateway</html>", ErrorCode.INFERENCE_UNAVAILABLE),
                // 200 without a usable glbPath.
                Arguments.of(200, "{}", ErrorCode.CONVERSION_FAILED),
                Arguments.of(200, "{\"glbPath\":\"\"}", ErrorCode.CONVERSION_FAILED),
                Arguments.of(200, "not json", ErrorCode.CONVERSION_FAILED));
    }

    @ParameterizedTest(name = "HTTP {0} {1} -> {2}")
    @MethodSource("answers")
    void everyAnswerBecomesAJobFailureCode(int status, String body, ErrorCode expected) {
        answer(status, body);

        InferenceFailure failure = catchThrowableOfType(
                () -> client(5_000).infer("job-2", List.of(dir.resolve("dog.jpg"))), InferenceFailure.class);

        assertThat(failure).as("HTTP %d %s must fail the run", status, body).isNotNull();
        assertThat(failure.code()).isEqualTo(expected);
        assertThat(failure.getMessage()).contains("HTTP " + status)
                .as("JobResponse.error.message never names server paths or internal hosts")
                .doesNotContain("/data/dog1.jpg").doesNotContain("127.0.0.1");
        assertThat(failure.logDetail()).contains("/infer");
    }

    @Test
    void noAnswerWithinTheTimeoutIsInferenceTimeout() {
        answer(200, "{}");
        delayMs = 3_000;
        long started = System.nanoTime();

        InferenceFailure failure = catchThrowableOfType(
                () -> client(300).infer("job-3", List.of(dir.resolve("dog.jpg"))), InferenceFailure.class);

        long tookMs = (System.nanoTime() - started) / 1_000_000;
        assertThat(failure.code()).isEqualTo(ErrorCode.INFERENCE_TIMEOUT);
        assertThat(tookMs).as("gives up at inference.timeout-ms, not when the service answers").isLessThan(2_500);
    }

    @Test
    void droppedConnectionIsInferenceUnavailable() {
        drop = true;

        InferenceFailure failure = catchThrowableOfType(
                () -> client(5_000).infer("job-4", List.of(dir.resolve("dog.jpg"))), InferenceFailure.class);

        assertThat(failure.code()).isEqualTo(ErrorCode.INFERENCE_UNAVAILABLE);
    }

    @Test
    void refusedConnectionIsInferenceUnavailable() throws IOException {
        int closedPort;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = probe.getLocalPort();
        }
        InferenceClient down = new InferenceClient("http://127.0.0.1:" + closedPort, 5_000, JSON);

        InferenceFailure failure = catchThrowableOfType(
                () -> down.infer("job-5", List.of(dir.resolve("dog.jpg"))), InferenceFailure.class);

        assertThat(failure.code()).isEqualTo(ErrorCode.INFERENCE_UNAVAILABLE);
    }

    private InferenceClient client(long timeoutMs) {
        // Trailing slash on purpose: the client must not call //infer.
        return new InferenceClient("http://127.0.0.1:" + server.getAddress().getPort() + "/", timeoutMs, JSON);
    }

    private void answer(int status, String body) {
        this.status = status;
        this.body = body;
    }

    private static String detail(String code, String message) {
        ObjectNode body = JSON.createObjectNode();
        body.putObject("detail").put("code", code).put("message", message);
        return body.toString();
    }
}
