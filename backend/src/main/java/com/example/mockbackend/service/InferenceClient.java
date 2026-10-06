package com.example.mockbackend.service;

import com.example.mockbackend.domain.RunDetails;
import com.example.mockbackend.exception.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Transport to the Python inference service, internal contract v0 (generation/inference_service/README.md):
 * POST {inference.base-url}/infer with absolute photo paths, answered with the path of the base GLB. Spring and
 * Python share a filesystem (same host or shared volume, REVIEW_CHECKLIST #6). If the contract meeting picks binary
 * transfer instead, only this class changes: upload the photos and save the returned GLB to a local file.
 * <p>
 * Job failure codes (docs/api/ERROR_CODES.md): a known {@code detail.code} in the error body wins, otherwise
 * HTTP 501-504 is INFERENCE_UNAVAILABLE and any other error status INFERENCE_FAILED. No connection (refused,
 * dropped, none within 5 s) is INFERENCE_UNAVAILABLE, no answer within inference.timeout-ms INFERENCE_TIMEOUT, and
 * a 200 without a usable glbPath CONVERSION_FAILED.
 */
@Component
@Profile("real")
public class InferenceClient {
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    /** Codes the inference service may send in detail.code. */
    private static final Set<ErrorCode> SERVICE_CODES = EnumSet.of(ErrorCode.INFERENCE_FAILED,
            ErrorCode.INFERENCE_TIMEOUT, ErrorCode.INFERENCE_UNAVAILABLE, ErrorCode.CONVERSION_FAILED);
    private static final int MAX_QUOTED_BODY = 300;
    /** job_runs.model_version column length. */
    private static final int MAX_MODEL_VERSION = 128;

    private final URI inferUri;
    private final Duration timeout;
    private final ObjectMapper objectMapper;
    private final HttpClient http;

    public InferenceClient(@Value("${inference.base-url:http://localhost:8001}") String baseUrl,
                           @Value("${inference.timeout-ms:600000}") long timeoutMs,
                           ObjectMapper objectMapper) {
        this.inferUri = URI.create(baseUrl.replaceAll("/+$", "") + "/infer");
        this.timeout = Duration.ofMillis(timeoutMs);
        this.objectMapper = objectMapper;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    /**
     * What /infer answered with: the GLB to serve, the METRICS.md section 2 numbers, the passthrough flag and the
     * optional model version (additive contract field; null when the service sends none).
     */
    public record Result(Path glbPath, JsonNode metrics, boolean passthrough, String modelVersion) {

        /** The per-run measurements for JobFinisher; {@code glbBytes} is the size of the GLB the server stored. */
        public RunDetails details(long glbBytes) {
            return new RunDetails(number("inferMs"), number("gpuPeakMB"), number("modelParams"), number("outputVertices"),
                    number("outputTriangles"), number("convertMs"), glbBytes, passthrough, modelVersion);
        }

        /** A metric counts only when it is a JSON number; absent, null or text (e.g. "n/a") becomes null, never an error. */
        private Long number(String name) {
            JsonNode node = metrics == null ? null : metrics.path(name);
            return node != null && node.isNumber() ? node.longValue() : null;
        }
    }

    public URI inferUri() {
        return inferUri;
    }

    /** Blocks until /infer answers or inference.timeout-ms passes. Interruptible (server shutdown). */
    public Result infer(String jobId, List<Path> images) throws InferenceFailure, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(inferUri)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody(jobId, images)))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpConnectTimeoutException e) {
            throw new InferenceFailure(ErrorCode.INFERENCE_UNAVAILABLE,
                    "No connection to the inference service within " + CONNECT_TIMEOUT.toMillis() + " ms",
                    "POST " + inferUri + ": " + e);
        } catch (HttpTimeoutException e) {
            throw new InferenceFailure(ErrorCode.INFERENCE_TIMEOUT,
                    "No answer from the inference service within inference.timeout-ms=" + timeout.toMillis(),
                    "POST " + inferUri + ": " + e);
        } catch (IOException e) {
            throw new InferenceFailure(ErrorCode.INFERENCE_UNAVAILABLE,
                    "Inference service unreachable (" + e.getClass().getSimpleName() + ")", "POST " + inferUri + ": " + e);
        }
        if (response.statusCode() != 200) {
            throw failureOf(response);
        }
        return resultOf(response.body());
    }

    private String requestBody(String jobId, List<Path> images) {
        ObjectNode body = objectMapper.createObjectNode().put("jobId", jobId);
        ArrayNode imagePaths = body.putArray("imagePaths");
        images.forEach(image -> imagePaths.add(image.toAbsolutePath().normalize().toString()));
        // The hair variant's place in v1.0 is a contract meeting topic (REVIEW_CHECKLIST #4); base only until then.
        body.putObject("options").put("hair", false);
        return body.toString();
    }

    private InferenceFailure failureOf(HttpResponse<String> response) {
        int status = response.statusCode();
        String code = null;
        String message = null;
        try {
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode detail = root == null ? null : root.path("detail");
            if (detail != null && detail.isObject()) {
                code = detail.path("code").asText(null);
                message = detail.path("message").asText(null);
            }
        } catch (JsonProcessingException notJson) {
            // FastAPI answers an unhandled exception with plain text; the status decides below.
        }
        ErrorCode mapped = serviceCode(code);
        if (mapped == null) {
            mapped = status >= 501 && status <= 504 ? ErrorCode.INFERENCE_UNAVAILABLE : ErrorCode.INFERENCE_FAILED;
        }
        String answered = "HTTP " + status + (code == null ? "" : " " + code);
        return new InferenceFailure(mapped, "Inference service answered " + answered,
                "POST " + inferUri + " -> " + answered + ": " + (message != null ? message : quote(response.body())));
    }

    private Result resultOf(String body) throws InferenceFailure {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new InferenceFailure(ErrorCode.CONVERSION_FAILED, "Inference service answered HTTP 200 that is not JSON",
                    "POST " + inferUri + " -> HTTP 200: " + quote(body));
        }
        String glbPath = root == null ? null : root.path("glbPath").asText(null);
        if (glbPath == null || glbPath.isBlank()) {
            throw new InferenceFailure(ErrorCode.CONVERSION_FAILED, "Inference service answered HTTP 200 without glbPath",
                    "POST " + inferUri + " -> HTTP 200: " + quote(body));
        }
        try {
            return new Result(Path.of(glbPath), root.path("metrics"), root.path("passthrough").asBoolean(false),
                    modelVersion(root));
        } catch (InvalidPathException e) {
            throw new InferenceFailure(ErrorCode.CONVERSION_FAILED, "Inference service answered HTTP 200 with an invalid glbPath",
                    "POST " + inferUri + " -> HTTP 200 glbPath=" + glbPath);
        }
    }

    /** Optional answer field: absent, null or blank is null; longer values are cut to the column length. */
    private static String modelVersion(JsonNode root) {
        String text = root.path("modelVersion").asText(null);
        if (text == null || text.isBlank()) {
            return null;
        }
        text = text.strip();
        return text.length() <= MAX_MODEL_VERSION ? text : text.substring(0, MAX_MODEL_VERSION);
    }

    private static ErrorCode serviceCode(String code) {
        for (ErrorCode known : SERVICE_CODES) {
            if (known.name().equals(code)) {
                return known;
            }
        }
        return null;
    }

    private static String quote(String body) {
        if (body == null || body.isBlank()) {
            return "(empty body)";
        }
        String text = body.strip();
        return text.length() <= MAX_QUOTED_BODY ? text : text.substring(0, MAX_QUOTED_BODY) + "...";
    }
}
