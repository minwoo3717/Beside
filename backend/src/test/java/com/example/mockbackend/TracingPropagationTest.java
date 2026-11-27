package com.example.mockbackend;

import com.example.mockbackend.config.TracingConfig;
import com.example.mockbackend.service.JobTracing;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
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
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Distributed tracing of a job run (W8, 2026-11-26; one trace with its request since 2026-11-27, obs-wave3 A2): with
 * tracing on (as on the VM; tests switch it off unless asked), a real-profile job in upload mode lives in the trace of the
 * request that dispatched it. Under the upload's server span are the photo check and save, the wait for a worker
 * (beside.job.dispatch-wait, the @Async queue) and the run beside.job.run, whose children are the stages and the
 * inference client spans; the app's W3C traceparent (trace id = its X-Request-Id) makes that the app's trace. Every
 * request to the inference service carries its client span's traceparent next to X-Request-Id, the run's log lines carry
 * the trace id, a retry runs in the retry request's trace, and nothing stays in the MDC after a span closes. A traceparent
 * with flags 00 keeps the old shape: the run is a trace of its own linked to the request. With tracing on and no
 * management.otlp.tracing.endpoint there is no exporter at all (no connection from tests or local runs). Spans are
 * collected in memory (a SimpleSpanProcessor bean next to Boot's own processor).
 */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureObservability(metrics = false)
@ActiveProfiles("real")
@ExtendWith(OutputCaptureExtension.class)
class TracingPropagationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TOKEN = "trace-token-5c1e";
    private static final byte[] GLB = RealJobWorkerTest.minimalGlb();
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G'};
    private static final InMemorySpanExporter SPANS = InMemorySpanExporter.create();

    @TempDir
    static Path storage;

    private static HttpServer inference;
    private static ExecutorService handlers;
    /** "METHOD path | X-Request-Id | traceparent" of every request the fake service got. */
    private static final List<String> requests = new CopyOnWriteArrayList<>();
    private static volatile int artifactStatus = 200;

    @TestConfiguration
    static class CollectSpans {
        @Bean
        SpanProcessor collectSpansInMemory() {
            return SimpleSpanProcessor.create(SPANS);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("inference.base-url", () -> "http://127.0.0.1:" + inference.getAddress().getPort());
        registry.add("inference.transfer", () -> "upload");
        registry.add("inference.token", () -> TOKEN);
        registry.add("inference.timeout-ms", () -> "5000");
        registry.add("storage.upload.dir", () -> storage.resolve("uploads").toString());
        registry.add("storage.result.dir", () -> storage.resolve("results").toString());
        registry.add("storage.tmp.dir", () -> storage.resolve("tmp").toString());
    }

    @BeforeAll
    static void startFakeInferenceService() throws IOException {
        inference = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        handlers = Executors.newCachedThreadPool();
        inference.setExecutor(handlers);
        inference.createContext("/infer/upload", exchange -> {
            try {
                record(exchange);
                exchange.getRequestBody().readAllBytes();
                ObjectNode ok = JSON.createObjectNode();
                ok.put("glbPath", "/gpu/work/base.glb");
                ok.put("glbUrl", "/artifacts/traced/base.glb");
                ok.putObject("metrics").put("inferMs", 4500);
                ok.put("passthrough", false);
                ok.put("modelVersion", "fake-traced-0");
                respond(exchange, 200, ok.toString().getBytes(StandardCharsets.UTF_8));
            } finally {
                exchange.close();
            }
        });
        inference.createContext("/artifacts/", exchange -> {
            try {
                record(exchange);
                if (exchange.getRequestMethod().equals("DELETE")) {
                    exchange.sendResponseHeaders(204, -1);
                } else if (artifactStatus != 200) {
                    respond(exchange, artifactStatus, "{\"detail\":\"no such artifact\"}".getBytes(StandardCharsets.UTF_8));
                } else {
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
        requests.clear();
        SPANS.reset();
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JobTracing tracing;

    @Autowired
    Tracer tracer;

    @Autowired
    ApplicationContext context;

    /** Spans of the request around the run: children of the server span, not of the run. */
    private static final List<String> REQUEST_SPANS =
            List.of("beside.upload.check", "beside.upload.save", JobTracing.DISPATCH_WAIT_SPAN);

    @Test
    void aJobRunIsInItsUploadsTraceFromTheCheckToTheCleanup(CapturedOutput output) throws Exception {
        String jobId = create("rid-trace-upload-1");
        SpanData root = awaitRoot(jobId, 1);
        String trace = root.getTraceId();

        // the upload's trace: the run is a child of the upload request's server span, which names the app's id
        SpanData server = named(spansOf(trace), "http post /api/v1/jobs");
        assertThat(server.getKind()).isEqualTo(SpanKind.SERVER);
        assertThat(server.getParentSpanContext().isValid()).as("no traceparent: the server span starts the trace").isFalse();
        assertThat(attr(server, TracingConfig.REQUEST_ID_TAG)).as("the app's X-Request-Id on the upload's span")
                .isEqualTo("rid-trace-upload-1");
        assertThat(root.getParentSpanId()).isEqualTo(server.getSpanId());
        assertThat(attr(root, "jobId")).isEqualTo(jobId);
        assertThat(attr(root, "attempt")).isEqualTo("1");
        assertThat(attr(root, "worker")).isEqualTo("real");
        assertThat(attr(root, "job.result")).isEqualTo("COMPLETED");
        assertThat(attr(root, JobTracing.REQUEST_ID_ATTRIBUTE)).isEqualTo("rid-trace-upload-1");
        assertThat(attr(root, JobTracing.DISPATCHED_FROM)).as("only a run in a trace of its own names its request").isNull();
        assertThat(root.getLinks()).isEmpty();

        // the request's own steps and the wait for a worker hang under the server span; the dispatch is outside the save
        List<SpanData> inTrace = spansOf(trace);
        assertThat(inTrace).extracting(SpanData::getName).containsExactlyInAnyOrder("http post /api/v1/jobs",
                "beside.upload.check", "beside.upload.save", JobTracing.DISPATCH_WAIT_SPAN, JobTracing.RUN_SPAN,
                "beside.job.cutout", "beside.job.slot-wait", "POST /infer/upload", "GET /artifacts/{jobId}/base.glb",
                "beside.job.store", "DELETE /artifacts/{jobId}");
        for (String name : REQUEST_SPANS) {
            assertThat(named(inTrace, name).getParentSpanId()).as(name).isEqualTo(server.getSpanId());
        }
        SpanData wait = named(inTrace, JobTracing.DISPATCH_WAIT_SPAN);
        assertThat(wait.getStartEpochNanos()).isLessThanOrEqualTo(root.getStartEpochNanos());
        assertThat(wait.getEndEpochNanos()).isGreaterThanOrEqualTo(wait.getStartEpochNanos());
        for (SpanData span : inTrace) {
            if (span != root && span != server && !REQUEST_SPANS.contains(span.getName())) {
                assertThat(span.getParentSpanId()).as(span.getName()).isEqualTo(root.getSpanId());
            }
        }
        SpanData post = named(inTrace, "POST /infer/upload");
        SpanData get = named(inTrace, "GET /artifacts/{jobId}/base.glb");
        SpanData delete = named(inTrace, "DELETE /artifacts/{jobId}");
        assertThat(List.of(post, get, delete)).allSatisfy(span -> assertThat(span.getKind()).isEqualTo(SpanKind.CLIENT));
        assertThat(attr(post, "http.status_code")).isEqualTo("200");
        assertThat(attr(get, "http.status_code")).isEqualTo("200");
        assertThat(attr(delete, "http.status_code")).isEqualTo("204");
        assertThat(attr(named(inTrace, "beside.job.store"), "glb.bytes")).isEqualTo(String.valueOf(GLB.length));
        // the service's own numbers on its call; one it did not send (gpuPeakMB, as in passthrough) is left out
        assertThat(attr(post, "inferMs")).isEqualTo("4500");
        assertThat(attr(post, "gpuPeakMB")).isNull();

        // each request carried its client span's traceparent next to X-Request-Id (CONTRACT §6)
        assertThat(requests).containsExactly(
                "POST /infer/upload | " + jobId + "-1 | 00-" + trace + "-" + post.getSpanId() + "-01",
                "GET /artifacts/traced/base.glb | " + jobId + "-1 | 00-" + trace + "-" + get.getSpanId() + "-01",
                "DELETE /artifacts/" + jobId + " | " + jobId + "-1 | 00-" + trace + "-" + delete.getSpanId() + "-01");

        // the run's lines carry the trace: the PROCESSING line names it, and the MDC puts it in the text pattern
        String processing = output.getOut().lines().filter(line -> line.contains("jobId=" + jobId + " stage=PROCESSING"))
                .findFirst().orElseThrow();
        assertThat(processing).contains("traceId=" + trace).contains("job=" + jobId + " trace=" + trace + "]");
        assertThat(output.getOut().lines().filter(line -> line.contains("jobId=" + jobId + " stage=COMPLETED")))
                .singleElement().asString().contains("trace=" + trace + "]");
    }

    @Test
    void theAppsTraceparentMakesItsFlowIdTheTraceOfTheUploadTheRunAndTheInferenceCalls() throws Exception {
        String flow = "0af7651916cd43dd8448eb211c80319c";   // the app's X-Request-Id for the flow, 32 lowercase hex
        String appSpan = "b7ad6b7169203331";
        String jobId = create(flow, "00-" + flow + "-" + appSpan + "-01");
        SpanData root = awaitRoot(jobId, 1);

        assertThat(root.getTraceId()).isEqualTo(flow);
        SpanData server = named(spansOf(flow), "http post /api/v1/jobs");
        assertThat(server.getParentSpanId()).as("the app's span (never exported) is the parent").isEqualTo(appSpan);
        assertThat(root.getParentSpanId()).isEqualTo(server.getSpanId());
        assertThat(named(spansOf(flow), JobTracing.DISPATCH_WAIT_SPAN).getParentSpanId()).isEqualTo(server.getSpanId());
        assertThat(attr(root, JobTracing.REQUEST_ID_ATTRIBUTE)).isEqualTo(flow);
        assertThat(requests).isNotEmpty().allSatisfy(line -> assertThat(line).contains(" | 00-" + flow + "-"));
    }

    @Test
    void aTraceparentThatIsNotSampledKeepsTheRunInATraceOfItsOwnLinkedToTheApp() throws Exception {
        String flow = "4bf92f3577b34da6a3ce929d0e0e4736";
        String jobId = create(flow, "00-" + flow + "-00f067aa0ba902b7-00");
        SpanData root = awaitRoot(jobId, 1);

        assertThat(root.getTraceId()).isNotEqualTo(flow);
        assertThat(root.getParentSpanContext().isValid()).isFalse();
        assertThat(attr(root, JobTracing.DISPATCHED_FROM)).isEqualTo(flow);
        assertThat(root.getLinks()).extracting(link -> link.getSpanContext().getTraceId()).containsExactly(flow);
        assertThat(spansOf(flow)).as("flags 00: the server records nothing of the request").isEmpty();
        assertThat(spansOf(root.getTraceId())).extracting(SpanData::getName).doesNotContain(JobTracing.DISPATCH_WAIT_SPAN);
    }

    @Test
    void aFailedRunIsMarkedAndItsRetryRunsInTheRetryRequestsTrace() throws Exception {
        artifactStatus = 404;
        String jobId = create("rid-trace-retry-1");
        SpanData first = awaitRoot(jobId, 1);
        assertThat(attr(first, "job.result")).isEqualTo("FAILED");
        assertThat(attr(first, "job.error_code")).isEqualTo("CONVERSION_FAILED");
        assertThat(first.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        SpanData download = named(spansOf(first.getTraceId()), "GET /artifacts/{jobId}/base.glb");
        assertThat(download.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(attr(download, "http.status_code")).isEqualTo("404");
        assertThat(attr(download, "beside.error_code")).isEqualTo("CONVERSION_FAILED");

        artifactStatus = 200;
        mvc.perform(post("/api/v1/jobs/{id}/retry", jobId).header("X-Request-Id", "rid-trace-retry-2"))
                .andExpect(status().isAccepted());
        SpanData second = awaitRoot(jobId, 2);
        assertThat(second.getTraceId()).as("the retry request's trace, nothing carried over on the pool thread")
                .isNotEqualTo(first.getTraceId());
        assertThat(attr(second, "job.result")).isEqualTo("COMPLETED");
        SpanData retryRequest = named(spansOf(second.getTraceId()), "http post /api/v1/jobs/{jobId}/retry");
        assertThat(attr(retryRequest, TracingConfig.REQUEST_ID_TAG)).isEqualTo("rid-trace-retry-2");
        assertThat(second.getParentSpanId()).isEqualTo(retryRequest.getSpanId());
        assertThat(attr(second, JobTracing.REQUEST_ID_ATTRIBUTE)).isEqualTo("rid-trace-retry-2");
        assertThat(named(spansOf(second.getTraceId()), JobTracing.DISPATCH_WAIT_SPAN).getParentSpanId())
                .isEqualTo(retryRequest.getSpanId());
        assertThat(requests).filteredOn(line -> line.startsWith("POST ")).extracting(line -> line.split(" \\| ")[1])
                .containsExactly(jobId + "-1", jobId + "-2");
    }

    @Test
    void spansPutTheirIdsInTheMdcAndTakeThemAwayAgain() {
        MDC.put(JobTracing.MDC_TRACE_ID, "outer-trace");
        try (JobTracing.Run run = tracing.startRun("job-mdc", 1, "test")) {
            String trace = run.traceId();
            assertThat(trace).hasSize(32).isEqualTo(MDC.get(JobTracing.MDC_TRACE_ID));
            String runSpan = MDC.get(JobTracing.MDC_SPAN_ID);
            try (JobTracing.Scoped stage = tracing.stage("beside.job.test")) {
                assertThat(MDC.get(JobTracing.MDC_TRACE_ID)).isEqualTo(trace);
                assertThat(MDC.get(JobTracing.MDC_SPAN_ID)).isNotEqualTo(runSpan);
                assertThat(tracer.currentSpan().context().traceId()).isEqualTo(trace);
            }
            assertThat(MDC.get(JobTracing.MDC_SPAN_ID)).isEqualTo(runSpan);
        } finally {
            assertThat(MDC.get(JobTracing.MDC_TRACE_ID)).isEqualTo("outer-trace");
            assertThat(MDC.get(JobTracing.MDC_SPAN_ID)).isNull();
            assertThat(tracer.currentSpan()).as("no span left in scope on this thread").isNull();
            MDC.clear();
        }
        // a second run on the same thread starts its own trace, not a child of anything left behind
        try (JobTracing.Run again = tracing.startRun("job-mdc", 2, "test")) {
            assertThat(again.traceId()).hasSize(32);
        }
        List<SpanData> roots = SPANS.getFinishedSpanItems().stream()
                .filter(span -> span.getName().equals(JobTracing.RUN_SPAN) && "job-mdc".equals(attr(span, "jobId")))
                .toList();
        assertThat(roots).hasSize(2).allSatisfy(span -> assertThat(span.getParentSpanContext().isValid()).isFalse());
        assertThat(roots.get(0).getTraceId()).isNotEqualTo(roots.get(1).getTraceId());
    }

    @Test
    void aShareLinksTokenKeepsOnlyItsFirstSixCharactersOnTheServerSpan() throws Exception {
        // 2026-11-26 pre-deploy review: Spring tags server spans with the raw URI (http.url) and Tempo keeps them 72 h;
        // the share routes are public and the token is the only key to a pet's model (openapi.yaml: 앞 6자만)
        String token = "AbCdEfGhIjKlMnOpQrStUv";
        mvc.perform(get("/api/v1/shares/" + token).header("X-Request-Id", "rid-trace-share-1"));
        mvc.perform(get("/s/" + token).header("X-Request-Id", "rid-trace-share-2"));
        List<SpanData> servers = SPANS.getFinishedSpanItems().stream()
                .filter(span -> span.getKind() == SpanKind.SERVER)
                .filter(span -> String.valueOf(attr(span, TracingConfig.REQUEST_ID_TAG)).startsWith("rid-trace-share-"))
                .toList();
        assertThat(servers).hasSize(2);
        assertThat(servers).extracting(span -> attr(span, TracingConfig.URL_TAG))
                .containsExactlyInAnyOrder("/api/v1/shares/AbCdEf...", "/s/AbCdEf...");
        assertThat(servers).allSatisfy(span -> assertThat(span.getAttributes().asMap().values())
                .noneMatch(value -> String.valueOf(value).contains(token)));
    }

    @Test
    void withoutAnEndpointNothingIsExported() {
        // Spring Boot 3.2 makes the OTLP exporter only for management.otlp.tracing.endpoint (TracingExportConfigTest);
        // this context has tracing on and no endpoint, as a local run: no exporter bean, so no connection attempts.
        assertThat(context.getBeansOfType(SpanExporter.class)).isEmpty();
        assertThat(context.getEnvironment().getProperty("management.otlp.tracing.endpoint")).isNull();
    }

    private String create(String requestId) throws Exception {
        return create(requestId, null);
    }

    private String create(String requestId, String traceparent) throws Exception {
        var request = multipart("/api/v1/jobs").file(new MockMultipartFile("photos", "dog.png", "image/png", PNG))
                .header("X-Request-Id", requestId);
        if (traceparent != null) {
            request = request.header("traceparent", traceparent);
        }
        MvcResult created = mvc.perform(request)
                .andExpect(status().isAccepted())
                .andReturn();
        return JSON.readTree(created.getResponse().getContentAsByteArray()).path("jobId").asText();
    }

    /** The root span of that attempt, once it has ended (after the cleanup DELETE). */
    private static SpanData awaitRoot(String jobId, int attempt) {
        await().pollInterval(Duration.ofMillis(50)).atMost(Duration.ofSeconds(10))
                .until(() -> root(jobId, attempt).isPresent());
        return root(jobId, attempt).orElseThrow();
    }

    private static Optional<SpanData> root(String jobId, int attempt) {
        return SPANS.getFinishedSpanItems().stream()
                .filter(span -> span.getName().equals(JobTracing.RUN_SPAN))
                .filter(span -> jobId.equals(attr(span, "jobId")) && String.valueOf(attempt).equals(attr(span, "attempt")))
                .findFirst();
    }

    private static List<SpanData> spansOf(String traceId) {
        return SPANS.getFinishedSpanItems().stream().filter(span -> span.getTraceId().equals(traceId)).toList();
    }

    private static SpanData named(List<SpanData> spans, String name) {
        return spans.stream().filter(span -> span.getName().equals(name)).findFirst().orElseThrow(() ->
                new AssertionError(name + " not in " + spans.stream().map(SpanData::getName).toList()));
    }

    private static String attr(SpanData span, String key) {
        Object value = span.getAttributes().asMap().entrySet().stream()
                .filter(entry -> entry.getKey().getKey().equals(key)).map(java.util.Map.Entry::getValue)
                .findFirst().orElse(null);
        return value == null ? null : String.valueOf(value);
    }

    private static void record(HttpExchange exchange) {
        requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath()
                + " | " + exchange.getRequestHeaders().getFirst("X-Request-Id")
                + " | " + exchange.getRequestHeaders().getFirst("traceparent"));
    }

    private static void respond(HttpExchange exchange, int status, byte[] bytes) throws IOException {
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
