package com.example.mockbackend;

import com.example.mockbackend.domain.RunDetails;
import com.example.mockbackend.exception.ErrorCode;
import com.example.mockbackend.service.InferenceClient;
import com.example.mockbackend.service.InferenceFailure;
import com.example.mockbackend.service.InferenceMetrics;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Spring side of the internal /infer contract (generation/inference_service/README.md) against a fake inference
 * service: the request it sends, and the job failure code (docs/api/ERROR_CODES.md) every kind of answer becomes.
 * Both transfer modes (CONTRACT §6, §6.1): path (POST /infer with absolute paths) and upload (POST /infer/upload with
 * the photo files, GET glbUrl, DELETE /artifacts/{jobId}), and the optional X-Inference-Token header.
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
    // Upload mode (CONTRACT §6.1): POST /infer/upload answers with status/body above, /artifacts/ with the fields below.
    private final List<List<MultipartPart>> uploads = new CopyOnWriteArrayList<>();
    /** "METHOD path token" of every request; token "(none)" when the header is absent. */
    private final List<String> calls = new CopyOnWriteArrayList<>();
    /** "METHOD X-Request-Id" of every request; "(none)" when the header is absent. */
    private final List<String> requestIds = new CopyOnWriteArrayList<>();
    /** beside.inference.request of the clients made by {@link #metered}. */
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private volatile int artifactStatus = 200;
    private volatile byte[] artifactBytes = "glTF-bytes-from-the-gpu-pc".getBytes(StandardCharsets.UTF_8);
    private volatile boolean artifactDrop;
    /** Headers and a few bytes of a long body, then silence (a GPU PC that vanished from the tailnet mid-transfer). */
    private volatile boolean artifactStall;
    /** No answer at all to the GET, not even headers (a Pod stopped between the POST answer and the download). */
    private volatile boolean artifactSilent;
    private volatile int deleteStatus = 204;

    @BeforeEach
    void startFakeInferenceService() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        handlers = Executors.newCachedThreadPool();
        server.setExecutor(handlers);
        server.createContext("/infer/upload", exchange -> {
            try {
                calls.add("POST " + exchange.getRequestURI().getPath() + " " + tokenOf(exchange));
                requestIds.add("POST " + requestIdOf(exchange));
                contentTypes.add(exchange.getRequestHeaders().getFirst("Content-Type"));
                uploads.add(MultipartPart.parse(exchange.getRequestHeaders().getFirst("Content-Type"),
                        exchange.getRequestBody().readAllBytes()));
                Thread.sleep(delayMs);
                if (drop) {
                    return;
                }
                respond(exchange, status, body.getBytes(StandardCharsets.UTF_8));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.createContext("/artifacts/", exchange -> {
            try {
                String method = exchange.getRequestMethod();
                calls.add(method + " " + exchange.getRequestURI().getPath() + " " + tokenOf(exchange));
                requestIds.add(method + " " + requestIdOf(exchange));
                if (method.equals("DELETE")) {
                    exchange.sendResponseHeaders(deleteStatus, -1);
                } else if (artifactDrop) {
                    return; // close without an answer
                } else if (artifactSilent) {
                    try {
                        Thread.sleep(60_000); // never answers; @AfterEach interrupts it
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return;
                } else if (artifactStall) {
                    exchange.sendResponseHeaders(200, 100_000);
                    exchange.getResponseBody().write("glTF-partial".getBytes(StandardCharsets.US_ASCII));
                    exchange.getResponseBody().flush();
                    try {
                        Thread.sleep(60_000); // never finishes; @AfterEach interrupts it
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return;
                } else if (artifactStatus != 200) {
                    respond(exchange, artifactStatus, "{\"detail\":\"artifact not found\"}".getBytes(StandardCharsets.UTF_8));
                } else {
                    exchange.getResponseHeaders().add("Content-Type", "model/gltf-binary");
                    respond(exchange, 200, artifactBytes);
                }
            } finally {
                exchange.close();
            }
        });
        server.createContext("/infer", exchange -> {
            try {
                calls.add("POST " + exchange.getRequestURI().getPath() + " " + tokenOf(exchange));
                requestIds.add("POST " + requestIdOf(exchange));
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

    // ---------------------------------------------------------------- upload mode (CONTRACT §6.1) and the token

    @Test
    void uploadModeSendsTheRequestPartAndThePhotoFilesThenDownloadsTheGlb() throws Exception {
        Path jpg = Files.write(dir.resolve("job-u1_dog.jpg"), new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2});
        Path png = Files.write(dir.resolve("job-u1_puppy.png"), new byte[]{(byte) 0x89, 'P', 'N', 'G', 0, '\r', '\n'});
        Path webp = Files.write(dir.resolve("job-u1_cat.webp"), "RIFF....WEBP".getBytes(StandardCharsets.US_ASCII));
        ObjectNode ok = JSON.createObjectNode();
        ok.put("glbPath", "/mnt/c/beside/work/job-u1/base.glb"); // the GPU PC's own path: Spring must not use it
        ok.put("glbUrl", "/artifacts/job-u1/base.glb");
        ok.putObject("metrics").put("inferMs", 12).put("outputTriangles", 19876);
        ok.put("passthrough", false);
        ok.put("modelVersion", "animallift-baseline@3f2a1c9");
        answer(200, ok.toString());

        InferenceClient.Result result = uploadClient("s3cret")
                .infer("job-u1", List.of(jpg, png, webp), List.of("dog.jpg", "강아지.png", "my\"cat.webp"));

        assertThat(result.glbPath()).as("downloaded to storage.tmp.dir/{jobId}/base.glb")
                .isEqualTo(dir.resolve("tmp").resolve("job-u1").resolve("base.glb"))
                .hasBinaryContent(artifactBytes);
        assertThat(result.metrics().path("inferMs").asInt()).isEqualTo(12);
        assertThat(result.passthrough()).isFalse();
        assertThat(result.modelVersion()).isEqualTo("animallift-baseline@3f2a1c9");
        assertThat(result.details(99L).outputTriangles()).isEqualTo(19876L);

        assertThat(calls).containsExactly("POST /infer/upload s3cret", "GET /artifacts/job-u1/base.glb s3cret");
        assertThat(contentTypes).singleElement().asString().startsWith("multipart/form-data; boundary=");
        assertThat(requests).as("nothing went to the path mode endpoint").isEmpty();

        List<MultipartPart> parts = uploads.get(0);
        assertThat(parts).extracting(MultipartPart::name).containsExactly("request", "photos", "photos", "photos");
        MultipartPart request = parts.get(0);
        assertThat(request.filename()).as("a text part, not a file").isNull();
        assertThat(request.contentType()).startsWith("application/json");
        JsonNode sent = JSON.readTree(request.bytes());
        assertThat(sent.path("jobId").asText()).isEqualTo("job-u1");
        assertThat(sent.path("options").path("hair").isBoolean()).isTrue();
        assertThat(sent.path("options").path("hair").asBoolean()).isFalse();
        assertThat(sent.has("imagePaths")).as("no server paths in upload mode").isFalse();

        assertThat(parts.subList(1, 4)).extracting(MultipartPart::filename)
                .as("original names; a quote is percent-encoded so the header stays parseable")
                .containsExactly("dog.jpg", "강아지.png", "my%22cat.webp");
        assertThat(parts.subList(1, 4)).extracting(MultipartPart::contentType)
                .containsExactly("image/jpeg", "image/png", "image/webp");
        assertThat(parts.get(1).bytes()).isEqualTo(Files.readAllBytes(jpg));
        assertThat(parts.get(2).bytes()).as("binary content, CR LF included, unchanged").isEqualTo(Files.readAllBytes(png));
        assertThat(parts.get(3).bytes()).isEqualTo(Files.readAllBytes(webp));
    }

    @ParameterizedTest(name = "upload mode: HTTP {0} {1} -> {2}")
    @MethodSource("answers")
    void everyAnswerBecomesAJobFailureCodeInUploadMode(int status, String body, ErrorCode expected) throws IOException {
        answer(status, body);
        Path photo = Files.write(dir.resolve("dog.jpg"), new byte[]{1, 2, 3});

        InferenceFailure failure = catchThrowableOfType(
                () -> uploadClient("").infer("job-u2", List.of(photo)), InferenceFailure.class);

        assertThat(failure).as("HTTP %d %s must fail the run", status, body).isNotNull();
        assertThat(failure.code()).isEqualTo(expected);
        assertThat(failure.getMessage()).contains("HTTP " + status)
                .doesNotContain("/data/dog1.jpg").doesNotContain("127.0.0.1");
        assertThat(failure.logDetail()).contains("/infer/upload");
        assertThat(calls).as("no download after a failed answer").containsExactly("POST /infer/upload (none)");
    }

    @ParameterizedTest(name = "upload mode 200 {0} -> CONVERSION_FAILED")
    @ValueSource(strings = {
            "{\"glbPath\":\"/mnt/c/beside/work/job-u3/base.glb\"}",
            "{\"glbUrl\":\"\"}",
            "{\"glbUrl\":null}",
            "{\"glbUrl\":\"artifacts/job-u3/base.glb\"}",
            "{\"glbUrl\":\"http://10.0.0.9:8001/artifacts/job-u3/base.glb\"}",
            "{\"glbUrl\":\"//10.0.0.9/artifacts/job-u3/base.glb\"}"})
    void uploadModeNeedsARelativeGlbUrl(String answer) throws IOException {
        answer(200, answer);
        Path photo = Files.write(dir.resolve("dog.jpg"), new byte[]{1, 2, 3});

        InferenceFailure failure = catchThrowableOfType(
                () -> uploadClient("").infer("job-u3", List.of(photo)), InferenceFailure.class);

        assertThat(failure.code()).isEqualTo(ErrorCode.CONVERSION_FAILED);
        assertThat(failure.getMessage()).contains("glbUrl").doesNotContain("10.0.0.9");
        assertThat(calls).as("only the base-url host is ever asked for the GLB").containsExactly("POST /infer/upload (none)");
    }

    @ParameterizedTest(name = "artifact {0} -> CONVERSION_FAILED")
    @ValueSource(strings = {"404", "500", "503", "drop"})
    void glbThatCannotBeDownloadedIsConversionFailed(String artifactAnswer) throws IOException {
        answerUploadOk("job-u4");
        if (artifactAnswer.equals("drop")) {
            artifactDrop = true;
        } else {
            artifactStatus = Integer.parseInt(artifactAnswer);
        }
        Path photo = Files.write(dir.resolve("dog.jpg"), new byte[]{1, 2, 3});

        InferenceFailure failure = catchThrowableOfType(
                () -> uploadClient("s3cret").infer("job-u4", List.of(photo)), InferenceFailure.class);

        assertThat(failure.code()).as("the model ran; the GLB did not arrive").isEqualTo(ErrorCode.CONVERSION_FAILED);
        assertThat(failure.getMessage()).doesNotContain("127.0.0.1").doesNotContain("s3cret");
        assertThat(failure.logDetail()).contains("/artifacts/job-u4/base.glb").doesNotContain("s3cret");
        assertThat(dir.resolve("tmp").resolve("job-u4").resolve("base.glb"))
                .as("an error body is never saved as the GLB").doesNotExist();
    }

    @Test
    void glbBodyThatStallsAfterTheHeadersIsConversionFailedWithinTheTimeout() throws IOException {
        answerUploadOk("job-u6");
        artifactStall = true;
        Path photo = Files.write(dir.resolve("dog.jpg"), new byte[]{1, 2, 3});
        InferenceClient client = new InferenceClient(baseUrl(), 1_500, "upload", "s3cret", dir.resolve("tmp").toString(), JSON);

        long started = System.nanoTime();
        InferenceFailure failure = catchThrowableOfType(() -> client.infer("job-u6", List.of(photo)), InferenceFailure.class);
        long tookMs = (System.nanoTime() - started) / 1_000_000;

        assertThat(failure.code()).isEqualTo(ErrorCode.CONVERSION_FAILED);
        assertThat(failure.getMessage()).contains("did not finish").doesNotContain("127.0.0.1");
        assertThat(tookMs).as("the whole download is bounded, not only the wait for headers").isLessThan(10_000);
    }

    /**
     * FD16b: a GLB download that stops getting data (body after the headers, or no headers at all) is cut off after
     * inference.download-idle-ms, long before inference.timeout-ms; the partial file is gone.
     */
    @ParameterizedTest(name = "{0}: cut off after the idle limit")
    @ValueSource(strings = {"stall after the headers", "no headers"})
    void glbDownloadWithoutDataForTheIdleLimitIsCutOffEarly(String how) throws IOException {
        answerUploadOk("job-u7");
        if (how.equals("no headers")) {
            artifactSilent = true;
        } else {
            artifactStall = true;
        }
        Path photo = Files.write(dir.resolve("dog.jpg"), new byte[]{1, 2, 3});
        InferenceClient client = new InferenceClient(baseUrl(), 60_000, "upload", "", dir.resolve("tmp").toString(), 600, JSON);

        long started = System.nanoTime();
        InferenceFailure failure = catchThrowableOfType(() -> client.infer("job-u7", List.of(photo)), InferenceFailure.class);
        long tookMs = (System.nanoTime() - started) / 1_000_000;

        assertThat(failure.code()).isEqualTo(ErrorCode.CONVERSION_FAILED);
        assertThat(failure.getMessage()).contains("stalled").contains("download-idle-ms=600").doesNotContain("127.0.0.1");
        assertThat(tookMs).as("the idle limit (0.6 s), not the 60 s timeout").isBetween(500L, 10_000L);
        assertThat(dir.resolve("tmp").resolve("job-u7").resolve("base.glb")).doesNotExist();
    }

    /** A large GLB that keeps coming, slowly, is not cut off: only a gap with no data counts. */
    @Test
    void aSlowDownloadThatKeepsGettingDataIsNotCutOff() throws Exception {
        answerUploadOk("job-u8");
        artifactBytes = new byte[64 * 1024];
        artifactBytes[0] = 'g';
        server.removeContext("/artifacts/");
        server.createContext("/artifacts/", exchange -> {
            try {
                calls.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
                if (!exchange.getRequestMethod().equals("GET")) {
                    exchange.sendResponseHeaders(204, -1);
                    return;
                }
                exchange.sendResponseHeaders(200, artifactBytes.length);
                for (int at = 0; at < artifactBytes.length; at += 8 * 1024) {   // 8 parts, 200 ms apart: 1.4 s in all
                    exchange.getResponseBody().write(artifactBytes, at, 8 * 1024);
                    exchange.getResponseBody().flush();
                    Thread.sleep(200);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        Path photo = Files.write(dir.resolve("dog.jpg"), new byte[]{1, 2, 3});
        InferenceClient client = new InferenceClient(baseUrl(), 60_000, "upload", "", dir.resolve("tmp").toString(), 600, JSON);

        InferenceClient.Result result = client.infer("job-u8", List.of(photo));

        assertThat(result.glbPath()).hasBinaryContent(artifactBytes);
    }

    /**
     * FD16b: the caller's Watch says the request went to a process that is gone (the worker: /healthz names another
     * instance). A POST is given up as INFERENCE_UNAVAILABLE in both modes, without waiting for the timeout.
     */
    @ParameterizedTest(name = "{0} mode: lost POST -> INFERENCE_UNAVAILABLE")
    @ValueSource(strings = {"path", "upload"})
    void aPostTheWatchCallsLostIsInferenceUnavailableAtOnce(String transfer) throws IOException {
        answerUploadOk("job-w1");
        delayMs = 20_000;   // the service never gets to answer
        Path photo = Files.write(dir.resolve("dog.jpg"), new byte[]{1, 2, 3});
        InferenceClient client = new InferenceClient(baseUrl(), 60_000, transfer, "", dir.resolve("tmp").toString(), JSON);
        long started = System.nanoTime();
        InferenceClient.Watch watch = () -> System.nanoTime() - started > 400_000_000L ? "instance aaa -> bbb" : null;

        InferenceFailure failure = catchThrowableOfType(
                () -> client.infer("job-w1", List.of(photo), List.of("dog.jpg"), watch), InferenceFailure.class);
        long tookMs = (System.nanoTime() - started) / 1_000_000;

        assertThat(failure.code()).isEqualTo(ErrorCode.INFERENCE_UNAVAILABLE);
        assertThat(failure.getMessage()).contains("restarted while this run was in flight");
        assertThat(failure.logDetail()).contains("instance aaa -> bbb");
        assertThat(tookMs).as("the watch, not the 60 s timeout").isLessThan(5_000);
        assertThat(calls).as("no download after a lost POST").noneMatch(call -> call.startsWith("GET"));
    }

    @Test
    void aDownloadTheWatchCallsLostIsConversionFailedAtOnce() throws IOException {
        answerUploadOk("job-w2");
        artifactStall = true;
        Path photo = Files.write(dir.resolve("dog.jpg"), new byte[]{1, 2, 3});
        // Idle limit off: only the watch can end it early.
        InferenceClient client = new InferenceClient(baseUrl(), 60_000, "upload", "", dir.resolve("tmp").toString(), 0, JSON);
        long started = System.nanoTime();
        InferenceClient.Watch watch = () -> calls.stream().anyMatch(call -> call.startsWith("GET"))
                && System.nanoTime() - started > 400_000_000L ? "instance aaa -> bbb" : null;

        InferenceFailure failure = catchThrowableOfType(
                () -> client.infer("job-w2", List.of(photo), List.of("dog.jpg"), watch), InferenceFailure.class);

        assertThat(failure.code()).isEqualTo(ErrorCode.CONVERSION_FAILED);
        assertThat(failure.getMessage()).contains("restarted during the GLB download");
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(5_000);
        assertThat(dir.resolve("tmp").resolve("job-w2").resolve("base.glb")).doesNotExist();
    }

    /**
     * Server stop (failover drill 2026-11-26, finding 4): a watch that throws InterruptedException (RealJobWorker's,
     * once ShutdownSignal is raised) gives up the POST or the download within the watch interval and rethrows it,
     * instead of keeping the run (and with it the server's stop) waiting for inference.timeout-ms. No GLB is left.
     */
    @ParameterizedTest(name = "stop during the {0}")
    @ValueSource(strings = {"post", "download"})
    void aWatchThatThrowsInterruptedGivesTheRequestUpAtOnce(String during) throws IOException {
        answerUploadOk("job-w4");
        if (during.equals("post")) {
            delayMs = 20_000;   // the service never gets to answer
        } else {
            artifactStall = true;
        }
        Path photo = Files.write(dir.resolve("dog.jpg"), new byte[]{1, 2, 3});
        InferenceClient client = new InferenceClient(baseUrl(), 60_000, "upload", "", dir.resolve("tmp").toString(), 0, JSON);
        long started = System.nanoTime();
        InterruptedException stop = new InterruptedException("server shutting down");
        InferenceClient.Watch watch = () -> {
            boolean reached = during.equals("post") || calls.stream().anyMatch(call -> call.startsWith("GET"));
            if (reached && System.nanoTime() - started > 300_000_000L) {
                throw stop;
            }
            return null;
        };

        InterruptedException thrown = catchThrowableOfType(
                () -> client.infer("job-w4", List.of(photo), List.of("dog.jpg"), watch), InterruptedException.class);

        assertThat(thrown).isSameAs(stop);
        assertThat((System.nanoTime() - started) / 1_000_000).as("the watch, not the 60 s timeout").isLessThan(5_000);
        assertThat(Thread.currentThread().isInterrupted()).as("no interrupt flag: nothing interrupted the thread").isFalse();
        assertThat(dir.resolve("tmp").resolve("job-w4").resolve("base.glb")).doesNotExist();
    }

    /** A watch that says nothing, or fails, never ends a run the service still answers. */
    @Test
    void aQuietOrBrokenWatchChangesNothing() throws Exception {
        answerUploadOk("job-w3");
        delayMs = 600;   // a few watch looks while the POST waits
        Path photo = Files.write(dir.resolve("dog.jpg"), new byte[]{1, 2, 3});
        InferenceClient client = uploadClient("");

        InferenceClient.Watch broken = () -> {
            throw new IllegalStateException("monitor bug");
        };
        assertThat(client.infer("job-w3", List.of(photo), List.of("dog.jpg"), broken).glbPath()).hasBinaryContent(artifactBytes);
        assertThat(client.infer("job-w3", List.of(photo), List.of("dog.jpg"), InferenceClient.Watch.NONE).glbPath())
                .hasBinaryContent(artifactBytes);
        assertThat(client.infer("job-w3", List.of(photo), List.of("dog.jpg"), null).glbPath()).hasBinaryContent(artifactBytes);
    }

    /**
     * FD4: a GLB this server cannot write (its own disk full or not writable) is INTERNAL_ERROR, not CONVERSION_FAILED:
     * the model and the converter did their part. Here the target is a folder (cannot be opened as a file), and the
     * temporary folder cannot be made (a file in its place).
     */
    @ParameterizedTest(name = "{0} -> INTERNAL_ERROR")
    @ValueSource(strings = {"cannot open the file", "cannot make the folder"})
    void aGlbThisServerCannotWriteIsInternalError(String how) throws IOException {
        answerUploadOk("job-d1");
        Path tmp = dir.resolve("tmp");
        if (how.equals("cannot open the file")) {
            Files.createDirectories(tmp.resolve("job-d1").resolve("base.glb"));
        } else {
            Files.write(tmp.getParent().resolve("tmp"), new byte[]{0});   // a file where the folder should be
        }
        Path photo = Files.write(dir.resolve("dog.jpg"), new byte[]{1, 2, 3});

        InferenceFailure failure = catchThrowableOfType(
                () -> uploadClient("").infer("job-d1", List.of(photo)), InferenceFailure.class);

        assertThat(failure.code()).isEqualTo(ErrorCode.INTERNAL_ERROR);
        assertThat(failure.getMessage()).contains("on this server").doesNotContain(dir.toString());
        assertThat(failure.logDetail()).contains("base.glb");
    }

    @ParameterizedTest(name = "{0} mode: HTTP {1} -> INFERENCE_UNAVAILABLE")
    @CsvSource({"path, 401", "path, 403", "upload, 401", "upload, 403"})
    void rejectedTokenIsInferenceUnavailableInBothModes(String transfer, int status) throws IOException {
        answer(status, "{\"detail\":\"Not authenticated\"}");
        Path photo = Files.write(dir.resolve("dog.jpg"), new byte[]{1, 2, 3});
        InferenceClient client = new InferenceClient(baseUrl(), 5_000, transfer, "wrong-token", dir.resolve("tmp").toString(), JSON);

        InferenceFailure failure = catchThrowableOfType(() -> client.infer("job-u5", List.of(photo)), InferenceFailure.class);

        assertThat(failure.code()).as("a token mismatch is a deployment problem, not a bad photo")
                .isEqualTo(ErrorCode.INFERENCE_UNAVAILABLE);
        assertThat(failure.getMessage()).contains("HTTP " + status).doesNotContain("wrong-token");
        assertThat(failure.logDetail()).doesNotContain("wrong-token");
    }

    @Test
    void tokenHeaderIsSentInPathModeOnlyWhenConfigured() throws Exception {
        ObjectNode ok = JSON.createObjectNode();
        ok.put("glbPath", dir.resolve("base.glb").toString());
        answer(200, ok.toString());

        new InferenceClient(baseUrl(), 5_000, "path", "s3cret", dir.resolve("tmp").toString(), JSON)
                .infer("job-u6", List.of(dir.resolve("dog.jpg")));
        client(5_000).infer("job-u7", List.of(dir.resolve("dog.jpg")));
        new InferenceClient(baseUrl(), 5_000, "path", "  ", dir.resolve("tmp").toString(), JSON)
                .infer("job-u8", List.of(dir.resolve("dog.jpg")));

        assertThat(calls).containsExactly("POST /infer s3cret", "POST /infer (none)", "POST /infer (none)");
        assertThat(requests).extracting(request -> request.path("jobId").asText()).containsExactly("job-u6", "job-u7", "job-u8");
    }

    @Test
    void cleanupDeletesTheTemporaryGlbAndTheServiceJobFolder() throws Exception {
        answerUploadOk("job-u9");
        Path photo = Files.write(dir.resolve("dog.jpg"), new byte[]{1, 2, 3});
        InferenceClient client = uploadClient("s3cret");
        Path glb = client.infer("job-u9", List.of(photo)).glbPath();
        assertThat(glb).exists();

        client.cleanup("job-u9");

        assertThat(dir.resolve("tmp").resolve("job-u9")).doesNotExist();
        assertThat(dir.resolve("tmp")).as("only the job's own folder is removed").exists();
        assertThat(calls).containsExactly("POST /infer/upload s3cret", "GET /artifacts/job-u9/base.glb s3cret",
                "DELETE /artifacts/job-u9 s3cret");
    }

    @Test
    void cleanupNeverThrowsAndDoesNothingInPathMode() throws IOException {
        deleteStatus = 500;
        uploadClient("").cleanup("job-u10"); // DELETE answered 500: logged only
        assertThat(calls).containsExactly("DELETE /artifacts/job-u10 (none)");

        int closedPort;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = probe.getLocalPort();
        }
        new InferenceClient("http://127.0.0.1:" + closedPort, 5_000, "upload", "", dir.resolve("tmp").toString(), JSON)
                .cleanup("job-u11"); // connection refused: logged only

        client(5_000).cleanup("job-u12");
        assertThat(calls).as("path mode has nothing to clean on the service").hasSize(1);
    }

    @Test
    void cleanupSendsTheDeleteOnceMoreWhenTheConnectionClosesWithoutAnAnswer() throws Exception {
        // The service closes the first connection after reading the request (a keep-alive close racing the DELETE,
        // seen in the 2026-11-18 load test as "HTTP/1.1 header parser received no bytes") and answers the second.
        List<String> requestLines = new CopyOnWriteArrayList<>();
        try (ServerSocket listener = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            listener.setSoTimeout(5_000);
            Thread service = new Thread(() -> {
                try {
                    try (Socket first = listener.accept()) {
                        requestLines.add(readRequestLine(first));
                    }
                    try (Socket second = listener.accept()) {
                        requestLines.add(readRequestLine(second));
                        second.getOutputStream().write(
                                "HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                        second.getOutputStream().flush();
                    }
                } catch (IOException ignored) {
                    // the assertion below reports what arrived
                }
            });
            service.start();

            new InferenceClient("http://127.0.0.1:" + listener.getLocalPort(), 5_000, "upload", "", dir.resolve("tmp").toString(), JSON)
                    .cleanup("job-u13");
            service.join(10_000);
        }
        assertThat(requestLines).containsExactly("DELETE /artifacts/job-u13 HTTP/1.1", "DELETE /artifacts/job-u13 HTTP/1.1");
    }

    @Test
    void cleanupGivesUpAfterTheSecondDeleteWithoutAnAnswer() throws Exception {
        List<String> requestLines = new CopyOnWriteArrayList<>();
        try (ServerSocket listener = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            listener.setSoTimeout(3_000);
            Thread service = new Thread(() -> {
                while (true) {
                    try (Socket connection = listener.accept()) {
                        requestLines.add(readRequestLine(connection));   // read, then close without answering
                    } catch (IOException e) {
                        return;   // accept timed out: no further attempt came
                    }
                }
            });
            service.start();

            new InferenceClient("http://127.0.0.1:" + listener.getLocalPort(), 5_000, "upload", "", dir.resolve("tmp").toString(), JSON)
                    .cleanup("job-u14"); // logged only, never thrown
            service.join(10_000);
        }
        assertThat(requestLines).as("one retry, not more").hasSize(2)
                .allMatch(line -> line.equals("DELETE /artifacts/job-u14 HTTP/1.1"));
    }

    @Test
    void cleanupDoesNotSendTheDeleteAgainAfterATimeout() throws Exception {
        // A stuck path or service: the request goes out and no answer comes. One CLEANUP_TIMEOUT (10 s) is the wait;
        // a second one would hold the GPU slot longer and could reach the service after a retry of the job started.
        List<String> requestLines = new CopyOnWriteArrayList<>();
        List<Socket> heldOpen = new CopyOnWriteArrayList<>();
        AtomicBoolean stop = new AtomicBoolean();
        long elapsedMs;
        try (ServerSocket listener = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            listener.setSoTimeout(200);
            Thread service = new Thread(() -> {
                while (!stop.get()) {
                    try {
                        Socket connection = listener.accept();
                        heldOpen.add(connection);   // read, then never answer and keep the connection open
                        requestLines.add(readRequestLine(connection));
                    } catch (IOException e) {
                        // accept timed out: look at stop again
                    }
                }
            });
            service.start();
            long started = System.nanoTime();
            new InferenceClient("http://127.0.0.1:" + listener.getLocalPort(), 5_000, "upload", "", dir.resolve("tmp").toString(), JSON)
                    .cleanup("job-u15"); // logged only, never thrown
            elapsedMs = (System.nanoTime() - started) / 1_000_000;
            stop.set(true);
            service.join(5_000);
        } finally {
            for (Socket connection : heldOpen) {
                connection.close();
            }
        }
        assertThat(requestLines).as("no second DELETE after a timeout").containsExactly("DELETE /artifacts/job-u15 HTTP/1.1");
        assertThat(elapsedMs).as("one CLEANUP_TIMEOUT, not two").isBetween(9_000L, 15_000L);
    }

    /** The request line of an HTTP/1.1 request on {@code socket}, after reading its headers (DELETE has no body). */
    private static String readRequestLine(Socket socket) throws IOException {
        InputStream in = socket.getInputStream();
        StringBuilder head = new StringBuilder();
        int b;
        while ((b = in.read()) != -1) {
            head.append((char) b);
            if (head.length() >= 4 && head.substring(head.length() - 4).equals("\r\n\r\n")) {
                break;
            }
        }
        int end = head.indexOf("\r\n");
        return end < 0 ? head.toString() : head.substring(0, end);
    }

    @Test
    void missingPhotoInUploadModeIsInferenceFailedWithoutCallingTheService() {
        InferenceFailure failure = catchThrowableOfType(
                () -> uploadClient("").infer("job-u13", List.of(dir.resolve("gone.jpg"))), InferenceFailure.class);

        assertThat(failure.code()).as("same code as the service's 'image not found' in path mode")
                .isEqualTo(ErrorCode.INFERENCE_FAILED);
        assertThat(failure.getMessage()).doesNotContain(dir.toString());
        assertThat(calls).isEmpty();
    }

    @Test
    void unknownTransferModeFailsAtStartup() {
        assertThatThrownBy(() -> new InferenceClient(baseUrl(), 5_000, "nfs", "", dir.toString(), JSON))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inference.transfer");
    }

    // ---------------------------------------------------------------- X-Request-Id and beside.inference.request

    /** Every per-job request names the run ({jobId}-{attempt}) for the service's log; the body stays as it was. */
    @Test
    void perJobRequestsCarryTheRequestIdAndTheBodyIsUnchanged() throws Exception {
        ObjectNode ok = JSON.createObjectNode();
        ok.put("glbPath", dir.resolve("base.glb").toString());
        answer(200, ok.toString());
        client(5_000).infer("job-r1", 2, List.of(dir.resolve("dog.jpg")), List.of("dog.jpg"), null);
        client(5_000).infer("job-r2", List.of(dir.resolve("dog.jpg")));   // no attempt given: the job id alone
        client(5_000).infer("job r3", 1, List.of(dir.resolve("dog.jpg")), List.of("dog.jpg"), null); // not plain: none

        assertThat(requestIds).containsExactly("POST job-r1-2", "POST job-r2", "POST (none)");
        assertThat(requests).allSatisfy(sent -> assertThat(fieldNames(sent))
                .as("the header is the only change: the body has the same fields")
                .containsExactly("jobId", "imagePaths", "options"));
        assertThat(requests.get(0).path("jobId").asText()).isEqualTo("job-r1");

        requestIds.clear();
        answerUploadOk("job-r4");
        Path photo = Files.write(dir.resolve("dog.jpg"), new byte[]{1, 2, 3});
        InferenceClient upload = uploadClient("s3cret");
        upload.infer("job-r4", 1, List.of(photo), List.of("dog.jpg"), InferenceClient.Watch.NONE);
        upload.cleanup("job-r4", 1);
        upload.cleanup("job-r4");

        assertThat(requestIds).containsExactly("POST job-r4-1", "GET job-r4-1", "DELETE job-r4-1", "DELETE job-r4");
        assertThat(fieldNames(JSON.readTree(uploads.get(0).get(0).bytes()))).containsExactly("jobId", "options");
    }

    @Test
    void everyPostIsTimedAsInferByHowItEnded() throws Exception {
        InferenceClient client = metered(baseUrl(), "path", 600, 0);
        assertThat(registry.get("beside.inference.request").timers()).as("4 calls x 4 outcomes, registered at 0")
                .hasSize(16).allMatch(timer -> timer.count() == 0);
        ObjectNode ok = JSON.createObjectNode();
        ok.put("glbPath", dir.resolve("base.glb").toString());
        List<Path> photo = List.of(dir.resolve("dog.jpg"));

        answer(200, ok.toString());
        client.infer("job-m1", photo);
        answer(200, "{}");   // the job fails CONVERSION_FAILED, but the service did answer 200
        catchThrowableOfType(() -> client.infer("job-m1", photo), InferenceFailure.class);
        answer(503, "");
        catchThrowableOfType(() -> client.infer("job-m1", photo), InferenceFailure.class);
        answer(200, ok.toString());
        delayMs = 3_000;     // longer than the 600 ms timeout
        catchThrowableOfType(() -> client.infer("job-m1", photo), InferenceFailure.class);
        delayMs = 0;
        drop = true;         // closed without an answer
        catchThrowableOfType(() -> client.infer("job-m1", photo), InferenceFailure.class);
        drop = false;
        delayMs = 3_000;     // the watch gives up first (FD16b)
        catchThrowableOfType(() -> client.infer("job-m1", 1, photo, List.of("dog.jpg"), () -> "instance a -> b"),
                InferenceFailure.class);

        assertThat(timedSoFar()).containsExactly("infer/error=1", "infer/ok=2", "infer/timeout=1", "infer/unavailable=2");
        assertThat(registry.get("beside.inference.request").tags("call", "infer", "outcome", "timeout").timer()
                .totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).as("the wait until giving up").isBetween(500.0, 2_500.0);

        int closedPort;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = probe.getLocalPort();
        }
        InferenceClient down = metered("http://127.0.0.1:" + closedPort, "path", 5_000, 0);
        catchThrowableOfType(() -> down.infer("job-m2", photo), InferenceFailure.class);
        assertThat(timed("infer", "unavailable")).as("connection refused").isEqualTo(3);
    }

    @Test
    void uploadModeTimesTheDownloadAndEveryDeleteApart() throws Exception {
        InferenceClient client = metered(baseUrl(), "upload", 5_000, 600);
        answerUploadOk("job-m3");
        Path photo = Files.write(dir.resolve("dog.jpg"), new byte[]{1, 2, 3});
        List<Path> photos = List.of(photo);

        client.infer("job-m3", photos);                       // infer ok, download ok
        artifactStatus = 404;
        catchThrowableOfType(() -> client.infer("job-m3", photos), InferenceFailure.class);   // download error
        artifactStatus = 200;
        artifactStall = true;                                 // no data for the 600 ms idle limit
        catchThrowableOfType(() -> client.infer("job-m3", photos), InferenceFailure.class);   // download timeout
        artifactStall = false;
        Files.createDirectories(dir.resolve("tmp").resolve("job-m4").resolve("base.glb"));   // FD4: cannot write here
        answerUploadOk("job-m4");
        catchThrowableOfType(() -> client.infer("job-m4", photos), InferenceFailure.class);   // download error
        client.cleanup("job-m3");                             // 204
        deleteStatus = 500;
        client.cleanup("job-m3");

        assertThat(timedSoFar()).containsExactly("cleanup/error=1", "cleanup/ok=1",
                "download/error=2", "download/ok=1", "download/timeout=1", "infer/ok=4");

        int closedPort;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = probe.getLocalPort();
        }
        metered("http://127.0.0.1:" + closedPort, "upload", 5_000, 600).cleanup("job-m5");
        assertThat(timed("cleanup", "unavailable")).as("refused, sent once more, refused again").isEqualTo(2);
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private InferenceClient client(long timeoutMs) {
        // Trailing slash on purpose: the client must not call //infer.
        return new InferenceClient("http://127.0.0.1:" + server.getAddress().getPort() + "/", timeoutMs, JSON);
    }

    private InferenceClient uploadClient(String token) {
        return new InferenceClient(baseUrl(), 5_000, "upload", token, dir.resolve("tmp").toString(), JSON);
    }

    /** Trailing slash on purpose: the client must not call //infer/upload or //artifacts. */
    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    private void answerUploadOk(String jobId) {
        ObjectNode ok = JSON.createObjectNode();
        ok.put("glbPath", "/mnt/c/beside/work/" + jobId + "/base.glb");
        ok.put("glbUrl", "/artifacts/" + jobId + "/base.glb");
        ok.putObject("metrics").put("inferMs", 5);
        ok.put("passthrough", true);
        answer(200, ok.toString());
    }

    private static String tokenOf(HttpExchange exchange) {
        String token = exchange.getRequestHeaders().getFirst("X-Inference-Token");
        return token == null ? "(none)" : token;
    }

    private static String requestIdOf(HttpExchange exchange) {
        String id = exchange.getRequestHeaders().getFirst("X-Request-Id");
        return id == null ? "(none)" : id;
    }

    /** A client timed into {@link #registry}; {@code idleMs} is inference.download-idle-ms. */
    private InferenceClient metered(String base, String transfer, long timeoutMs, long idleMs) {
        return new InferenceClient(base, timeoutMs, transfer, "", dir.resolve("tmp").toString(), idleMs, JSON,
                new InferenceMetrics(registry));
    }

    private long timed(String call, String outcome) {
        return registry.get("beside.inference.request").tags("call", call, "outcome", outcome).timer().count();
    }

    /** Every beside.inference.request series with a count above 0, as "call/outcome=count". */
    private List<String> timedSoFar() {
        return registry.get("beside.inference.request").timers().stream()
                .filter(timer -> timer.count() > 0)
                .map(timer -> timer.getId().getTag("call") + "/" + timer.getId().getTag("outcome") + "=" + timer.count())
                .sorted()
                .toList();
    }

    private static void respond(HttpExchange exchange, int status, byte[] bytes) throws IOException {
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
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
