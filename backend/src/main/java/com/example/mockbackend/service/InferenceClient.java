package com.example.mockbackend.service;

import com.example.mockbackend.domain.RunDetails;
import com.example.mockbackend.exception.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

/**
 * Transport to the Python inference service (generation/inference_service/README.md, docs/CONTRACT.md §6), in one
 * of two modes chosen by inference.transfer:
 * <ul>
 *   <li>{@code path} (default): POST {inference.base-url}/infer with absolute photo paths, answered with the path of
 *       the base GLB. Spring and Python share a filesystem (same PC or shared volume, scripts/run_real.ps1).</li>
 *   <li>{@code upload} (REVIEW_CHECKLIST #6 (b), 2026-10-30): no shared disk (Spring on a VM, the service on the GPU
 *       PC over Tailscale). POST /infer/upload with a {@code request} JSON part and the photo files, then GET the
 *       answer's {@code glbUrl} (relative to the base URL) into {storage.tmp.dir}/{jobId}/base.glb.
 *       {@link #cleanup(String)} afterwards deletes that file and sends DELETE /artifacts/{jobId}.</li>
 * </ul>
 * When inference.token is not empty every request carries it as {@value #TOKEN_HEADER} (both modes).
 * <p>
 * Job failure codes (docs/api/ERROR_CODES.md), both modes: a known {@code detail.code} in the error body wins,
 * otherwise HTTP 401/403 (token missing or wrong, a proxy refusing) and 501-504 are INFERENCE_UNAVAILABLE and any
 * other error status INFERENCE_FAILED. No connection (refused, dropped, none within 5 s) is INFERENCE_UNAVAILABLE, no
 * answer within inference.timeout-ms INFERENCE_TIMEOUT, and a 200 without a usable glbPath (path mode) or glbUrl
 * (upload mode: absent, blank, not starting with a single "/") CONVERSION_FAILED. Upload mode only: a GLB download
 * that does not end in 200 (other status, connection error, timeout) is CONVERSION_FAILED, and a stored photo Spring
 * cannot read is INFERENCE_FAILED (what the path mode service answers for "image not found").
 * The worker checks the GLB header and copies it either way (RealJobWorker).
 */
@Component
@Profile("real")
public class InferenceClient {
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    /** DELETE /artifacts/{jobId} is housekeeping after the run ended: give up quickly. */
    static final Duration CLEANUP_TIMEOUT = Duration.ofSeconds(10);
    public static final String TOKEN_HEADER = "X-Inference-Token";
    /** Codes the inference service may send in detail.code. */
    private static final Set<ErrorCode> SERVICE_CODES = EnumSet.of(ErrorCode.INFERENCE_FAILED,
            ErrorCode.INFERENCE_TIMEOUT, ErrorCode.INFERENCE_UNAVAILABLE, ErrorCode.CONVERSION_FAILED);
    private static final int MAX_QUOTED_BODY = 300;
    /** job_runs.model_version column length. */
    private static final int MAX_MODEL_VERSION = 128;

    /** How photos reach the service and the GLB comes back (inference.transfer). */
    public enum Transfer {
        PATH, UPLOAD;

        static Transfer of(String value) {
            for (Transfer transfer : values()) {
                if (value != null && transfer.name().equalsIgnoreCase(value.strip())) {
                    return transfer;
                }
            }
            throw new IllegalArgumentException("inference.transfer must be path or upload, not '" + value + "'");
        }
    }

    private final String baseUrl;
    private final URI inferUri;
    private final URI uploadUri;
    private final Transfer transfer;
    /** Null when inference.token is empty: no header is sent. Never logged. */
    private final String token;
    private final Path tmpDir;
    private final Duration timeout;
    private final ObjectMapper objectMapper;
    private final HttpClient http;
    private final Logger log = LoggerFactory.getLogger(InferenceClient.class);

    @Autowired
    public InferenceClient(@Value("${inference.base-url:http://localhost:8001}") String baseUrl,
                           @Value("${inference.timeout-ms:600000}") long timeoutMs,
                           @Value("${inference.transfer:path}") String transfer,
                           @Value("${inference.token:}") String token,
                           @Value("${storage.tmp.dir:storage/tmp}") String tmpDir,
                           ObjectMapper objectMapper) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.inferUri = URI.create(this.baseUrl + "/infer");
        this.uploadUri = URI.create(this.baseUrl + "/infer/upload");
        this.transfer = Transfer.of(transfer);
        this.token = token == null || token.isBlank() ? null : token.strip();
        this.tmpDir = Path.of(tmpDir);
        this.timeout = Duration.ofMillis(timeoutMs);
        this.objectMapper = objectMapper;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        log.info("inference transfer={} url={} token={}", this.transfer.name().toLowerCase(Locale.ROOT), requestUri(),
                this.token == null ? "none" : "set");
    }

    /** Path mode without a token. */
    public InferenceClient(String baseUrl, long timeoutMs, ObjectMapper objectMapper) {
        this(baseUrl, timeoutMs, "path", "", "storage/tmp", objectMapper);
    }

    /**
     * What the service answered with: the GLB to serve (path mode: the service's file; upload mode: the downloaded
     * temporary file), the METRICS.md section 2 numbers, the passthrough flag and the optional model version
     * (additive contract field; null when the service sends none).
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

    public Transfer transfer() {
        return transfer;
    }

    /** The endpoint a run calls in this mode (/infer or /infer/upload), for the log. */
    public URI requestUri() {
        return transfer == Transfer.UPLOAD ? uploadUri : inferUri;
    }

    /** Photos named after their stored files. */
    public Result infer(String jobId, List<Path> images) throws InferenceFailure, InterruptedException {
        List<String> names = images.stream()
                .map(image -> image.getFileName() == null ? "upload" : image.getFileName().toString())
                .toList();
        return infer(jobId, images, names);
    }

    /**
     * Blocks until the service answers (and, in upload mode, the GLB is downloaded) or inference.timeout-ms passes.
     * Interruptible (server shutdown). {@code filenames} are the uploads' original names, sent in upload mode.
     */
    public Result infer(String jobId, List<Path> images, List<String> filenames) throws InferenceFailure, InterruptedException {
        return transfer == Transfer.UPLOAD ? inferUpload(jobId, images, filenames) : inferPath(jobId, images);
    }

    /**
     * After the worker stored the GLB or gave up: upload mode deletes {storage.tmp.dir}/{jobId} and asks the service
     * to delete the job's folder (DELETE /artifacts/{jobId}). Never throws: a failed cleanup is logged and does not
     * change the job's result (JobFinisher rule). Path mode has nothing to clean.
     */
    public void cleanup(String jobId) {
        if (transfer != Transfer.UPLOAD) {
            return;
        }
        Path jobTmp = tmpDir.resolve(jobId);
        try {
            deleteRecursively(jobTmp);
        } catch (IOException | RuntimeException e) {
            log.warn("jobId={} could not delete the temporary GLB folder {}: {}", jobId, jobTmp, e.toString());
        }
        String call = "DELETE " + baseUrl + "/artifacts/" + jobId;
        try {
            HttpRequest request = withToken(HttpRequest.newBuilder(URI.create(baseUrl + "/artifacts/" + jobId)))
                    .timeout(CLEANUP_TIMEOUT)
                    .DELETE()
                    .build();
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() / 100 != 2) {
                log.warn("jobId={} {} -> HTTP {}: the service keeps the job's files", jobId, call, response.statusCode());
            }
        } catch (IOException | RuntimeException e) {
            log.warn("jobId={} {} failed: {}: the service keeps the job's files", jobId, call, e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("jobId={} {} interrupted: the service keeps the job's files", jobId, call);
        }
    }

    // ---------------------------------------------------------------- path mode

    private Result inferPath(String jobId, List<Path> images) throws InferenceFailure, InterruptedException {
        String call = "POST " + inferUri;
        HttpRequest request = withToken(HttpRequest.newBuilder(inferUri))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(pathRequestBody(jobId, images)))
                .build();
        JsonNode root = answerOf(send(request, call), call);
        String glbPath = root.path("glbPath").asText(null);
        if (glbPath == null || glbPath.isBlank()) {
            throw new InferenceFailure(ErrorCode.CONVERSION_FAILED, "Inference service answered HTTP 200 without glbPath",
                    call + " -> HTTP 200: " + quote(root.toString()));
        }
        try {
            return resultOf(root, Path.of(glbPath));
        } catch (InvalidPathException e) {
            throw new InferenceFailure(ErrorCode.CONVERSION_FAILED, "Inference service answered HTTP 200 with an invalid glbPath",
                    call + " -> HTTP 200 glbPath=" + glbPath);
        }
    }

    private String pathRequestBody(String jobId, List<Path> images) {
        ObjectNode body = objectMapper.createObjectNode().put("jobId", jobId);
        ArrayNode imagePaths = body.putArray("imagePaths");
        images.forEach(image -> imagePaths.add(image.toAbsolutePath().normalize().toString()));
        // The hair variant's place in v1.0 is a contract meeting topic (REVIEW_CHECKLIST #4); base only until then.
        body.putObject("options").put("hair", false);
        return body.toString();
    }

    // ---------------------------------------------------------------- upload mode

    private Result inferUpload(String jobId, List<Path> images, List<String> filenames) throws InferenceFailure, InterruptedException {
        String call = "POST " + uploadUri;
        String boundary = "BesideBoundary" + UUID.randomUUID().toString().replace("-", "");
        HttpRequest request = withToken(HttpRequest.newBuilder(uploadUri))
                .timeout(timeout)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(multipartBody(boundary, jobId, images, filenames))
                .build();
        JsonNode root = answerOf(send(request, call), call);
        String glbUrl = root.path("glbUrl").asText(null);
        URI artifact = artifactUri(glbUrl);
        if (artifact == null) {
            throw new InferenceFailure(ErrorCode.CONVERSION_FAILED, "Inference service answered HTTP 200 without a usable glbUrl",
                    call + " -> HTTP 200 glbUrl=" + glbUrl + ": " + quote(root.toString()));
        }
        return resultOf(root, download(jobId, artifact));
    }

    /** glbUrl is relative to the service ("/artifacts/..."): the GLB is only ever fetched from inference.base-url. */
    private URI artifactUri(String glbUrl) {
        if (glbUrl == null || !glbUrl.startsWith("/") || glbUrl.startsWith("//")) {
            return null;
        }
        try {
            return URI.create(baseUrl + glbUrl);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** {@code request} JSON part, then one {@code photos} file part per photo (CONTRACT §6.1). */
    private HttpRequest.BodyPublisher multipartBody(String boundary, String jobId, List<Path> images, List<String> filenames)
            throws InferenceFailure {
        ObjectNode request = objectMapper.createObjectNode().put("jobId", jobId);
        request.putObject("options").put("hair", false); // as in path mode (REVIEW_CHECKLIST #4)
        List<HttpRequest.BodyPublisher> parts = new ArrayList<>();
        parts.add(text("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"request\"\r\n"
                + "Content-Type: application/json\r\n\r\n"
                + request + "\r\n"));
        for (int i = 0; i < images.size(); i++) {
            Path image = images.get(i);
            String name = i < filenames.size() && filenames.get(i) != null && !filenames.get(i).isBlank()
                    ? filenames.get(i) : "upload";
            parts.add(text("--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"photos\"; filename=\"" + headerFilename(name) + "\"\r\n"
                    + "Content-Type: " + imageType(name) + "\r\n\r\n"));
            try {
                if (!Files.isRegularFile(image)) {
                    throw new FileNotFoundException(image.toString());
                }
                parts.add(HttpRequest.BodyPublishers.ofFile(image));
            } catch (FileNotFoundException e) {
                throw new InferenceFailure(ErrorCode.INFERENCE_FAILED, "An uploaded photo is missing on the server",
                        "POST " + uploadUri + " photo=" + image);
            }
            parts.add(text("\r\n"));
        }
        parts.add(text("--" + boundary + "--\r\n"));
        return HttpRequest.BodyPublishers.concat(parts.toArray(HttpRequest.BodyPublisher[]::new));
    }

    private static HttpRequest.BodyPublisher text(String text) {
        return HttpRequest.BodyPublishers.ofString(text, StandardCharsets.UTF_8);
    }

    /** UTF-8 as is; a quote or line break would end the header, so they are percent-encoded as browsers do. */
    private static String headerFilename(String name) {
        return name.replace("\"", "%22").replace("\r", "%0D").replace("\n", "%0A");
    }

    private static String imageType(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        return "application/octet-stream";
    }

    /** GET glbUrl into {storage.tmp.dir}/{jobId}/base.glb. Only a 200 body is written; anything else is CONVERSION_FAILED. */
    private Path download(String jobId, URI artifact) throws InferenceFailure, InterruptedException {
        String call = "GET " + artifact;
        Path target = tmpDir.resolve(jobId).resolve("base.glb");
        try {
            Files.createDirectories(target.getParent());
        } catch (IOException e) {
            throw new InferenceFailure(ErrorCode.CONVERSION_FAILED,
                    "Could not prepare the GLB download (" + e.getClass().getSimpleName() + ")", call + " -> " + target + ": " + e);
        }
        HttpRequest request = withToken(HttpRequest.newBuilder(artifact)).timeout(timeout).GET().build();
        HttpResponse<Path> response;
        // The request timeout of the JDK 17 client stops once the headers arrive, so a GPU PC that vanishes mid-body
        // (no FIN over the tailnet) would block here for good while holding the only inference slot. The deadline
        // below covers the whole exchange, body included.
        CompletableFuture<HttpResponse<Path>> pending = http.sendAsync(request, info -> info.statusCode() == 200
                ? HttpResponse.BodySubscribers.ofFile(target, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)
                : HttpResponse.BodySubscribers.<Path>replacing(null));
        try {
            response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.cancel(true);
            deleteQuietly(target);
            throw new InferenceFailure(ErrorCode.CONVERSION_FAILED,
                    "The GLB download did not finish within inference.timeout-ms=" + timeout.toMillis(), call + ": body timed out");
        } catch (ExecutionException e) {
            deleteQuietly(target);
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new InferenceFailure(ErrorCode.CONVERSION_FAILED,
                    "Could not download the GLB from the inference service (" + cause.getClass().getSimpleName() + ")", call + ": " + cause);
        } catch (InterruptedException e) {
            pending.cancel(true);
            deleteQuietly(target);
            throw e;
        }
        if (response.statusCode() != 200) {
            throw new InferenceFailure(ErrorCode.CONVERSION_FAILED,
                    "Inference service answered HTTP " + response.statusCode() + " to the GLB download",
                    call + " -> HTTP " + response.statusCode());
        }
        return target;
    }

    // ---------------------------------------------------------------- shared

    private HttpRequest.Builder withToken(HttpRequest.Builder builder) {
        if (token != null) {
            builder.header(TOKEN_HEADER, token);
        }
        return builder;
    }

    private HttpResponse<String> send(HttpRequest request, String call) throws InferenceFailure, InterruptedException {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpConnectTimeoutException e) {
            throw new InferenceFailure(ErrorCode.INFERENCE_UNAVAILABLE,
                    "No connection to the inference service within " + CONNECT_TIMEOUT.toMillis() + " ms", call + ": " + e);
        } catch (HttpTimeoutException e) {
            throw new InferenceFailure(ErrorCode.INFERENCE_TIMEOUT,
                    "No answer from the inference service within inference.timeout-ms=" + timeout.toMillis(), call + ": " + e);
        } catch (IOException e) {
            throw new InferenceFailure(ErrorCode.INFERENCE_UNAVAILABLE,
                    "Inference service unreachable (" + e.getClass().getSimpleName() + ")", call + ": " + e);
        }
    }

    /** The JSON body of a 200; any other status becomes its job failure code. */
    private JsonNode answerOf(HttpResponse<String> response, String call) throws InferenceFailure {
        if (response.statusCode() != 200) {
            throw failureOf(response, call);
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(response.body());
        } catch (JsonProcessingException e) {
            root = null;
        }
        if (root == null || !root.isObject()) {
            throw new InferenceFailure(ErrorCode.CONVERSION_FAILED, "Inference service answered HTTP 200 that is not a JSON object",
                    call + " -> HTTP 200: " + quote(response.body()));
        }
        return root;
    }

    private InferenceFailure failureOf(HttpResponse<String> response, String call) {
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
            boolean unavailable = status == 401 || status == 403 || (status >= 501 && status <= 504);
            mapped = unavailable ? ErrorCode.INFERENCE_UNAVAILABLE : ErrorCode.INFERENCE_FAILED;
        }
        String answered = "HTTP " + status + (code == null ? "" : " " + code);
        return new InferenceFailure(mapped, "Inference service answered " + answered,
                call + " -> " + answered + ": " + (message != null ? message : quote(response.body())));
    }

    private static Result resultOf(JsonNode root, Path glb) {
        return new Result(glb, root.path("metrics"), root.path("passthrough").asBoolean(false), modelVersion(root));
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

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // cleanup(jobId) tries the whole folder again
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
