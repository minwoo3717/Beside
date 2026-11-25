package com.example.mockbackend.exception;

import com.example.mockbackend.api.v1.dto.ErrorDetail;
import com.example.mockbackend.api.v1.dto.ErrorResponse;
import com.example.mockbackend.config.RequestIdFilter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * One handler, two response shapes:
 * <ul>
 *   <li>Requests under {@code /api/v1/} get the contract envelope {@code { error: { code, message, jobId? } }}.</li>
 *   <li>Everything else (v0 {@code /api/jobs}, static pages) keeps the frozen flat shape {@code { error: "text" }}.</li>
 * </ul>
 * The split is by request path, not by controller, because 413 (multipart limits) is raised before a
 * handler is mapped and a controller-scoped advice would not see it.
 * <p>
 * Every error it answers is logged and counted (docs/METRICS.md §5.1 {@code beside.api.errors}, 2026-11-25 mentoring
 * 'log all errors'):
 * <ul>
 *   <li>5xx: ERROR with method, path (share tokens cut, {@link #pathForLog}), error code, status, request id and the
 *       stack. QUEUE_FULL (503) is the exception: an intended refusal that AdmissionGate logs once with the queue
 *       numbers, so here it stays DEBUG.</li>
 *   <li>4xx: one WARN line {@code METHOD PATH CODE (HTTP status): detail}, no stack. A route that does not exist (404
 *       from Spring: scanners, typos) is DEBUG, so probes of random paths do not fill the log.</li>
 *   <li>A client that left while the response was written: DEBUG, not counted (there is no response).</li>
 * </ul>
 * Spring MVC's own errors (wrong method or media type, ...) count with code {@code FRAMEWORK}; the v1 body keeps its
 * NOT_FOUND / INVALID_REQUEST / INTERNAL_ERROR code. The request id is the MDC entry of {@link RequestIdFilter}.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    static final String V1_PREFIX = "/api/v1/";
    /** The {@code code} tag of errors Spring MVC answers itself (ErrorResponse exceptions without a handler here). */
    public static final String FRAMEWORK = "FRAMEWORK";
    /** /s/{token}, /api/v1/shares/{token}[/asset]: group 1 the prefix, group 2 the token's first 6 characters. */
    private static final Pattern SHARE_TOKEN_IN_PATH =
            Pattern.compile("^(/+(?:s|api/v1/shares)/+)([A-Za-z0-9_-]{6})[A-Za-z0-9_-]+", Pattern.CASE_INSENSITIVE);
    private static final String UPLOAD_TOO_LARGE = "Upload too large: maximum 5 MB per photo and 20 MB per request";
    private static final Set<String> DISCONNECT_TYPE_NAMES =
            Set.of("ClientAbortException", "EOFException", "EofException", "AsyncRequestNotUsableException");
    /** Client error line: method, path, code, status, detail. Also the DEBUG lines (QUEUE_FULL, unknown route). */
    private static final String CLIENT_LINE = "{} {} {} (HTTP {}): {}";
    /** Server error line: method, path, code, status, request id; the exception follows as the stack. */
    private static final String SERVER_LINE = "Internal error: {} {} {} (HTTP {}, requestId={})";

    private final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final MeterRegistry registry;
    private final Map<String, Counter> errors = new ConcurrentHashMap<>();

    public GlobalExceptionHandler(MeterRegistry registry) {
        this.registry = registry;
        // Every HTTP error code exists from startup at 0 with its status, so a rate is defined before the first one.
        // FRAMEWORK series appear with their first error (the status is Spring's).
        for (ErrorCode code : ErrorCode.values()) {
            if (code.httpStatus() != null) {
                counter(code.httpStatus().value(), code.name());
            }
        }
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<?> handleApi(ApiException ex, HttpServletRequest request) {
        int status = ex.getStatus().value();
        String code = ex.getCode().name();
        if (ex.getRetryAfterSeconds() != null) {
            // QUEUE_FULL: an intended refusal under load, logged once with the queue numbers by AdmissionGate.
            log.debug(CLIENT_LINE, method(request), path(request), code, status, forLog(ex.getMessage()));
        } else if (status >= 500) {
            logServerError(request, code, status, ex);
        } else {
            log.warn(CLIENT_LINE, method(request), path(request), code, status, forLog(ex.getMessage()));
        }
        count(status, code);
        if (isV1(request)) {
            ResponseEntity<ErrorResponse> body = v1(ex.getStatus(), ex.getCode(), ex.getMessage(), ex.getJobId());
            if (ex.getRetryAfterSeconds() == null) {
                return body;
            }
            return ResponseEntity.status(body.getStatusCode()).headers(body.getHeaders())
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds())).body(body.getBody());
        }
        return flat(ex.getStatus(), ex.getMessage());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<?> handleMissingResource(NoResourceFoundException ex, HttpServletRequest request) {
        // No such route: mostly scanners and typos. DEBUG keeps them out of the log; the counter still sees them.
        log.debug(CLIENT_LINE, method(request), path(request), ErrorCode.NOT_FOUND, 404, "no such route");
        count(404, ErrorCode.NOT_FOUND.name());
        if (isV1(request)) {
            return v1(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, "Resource not found: " + request.getRequestURI(), null);
        }
        return flat(HttpStatus.NOT_FOUND, "Resource not found");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<?> handleUploadTooLarge(MaxUploadSizeExceededException ex, HttpServletRequest request) {
        clientError(request, ErrorCode.PAYLOAD_TOO_LARGE, forLog(ex.getMessage()));
        if (isV1(request)) {
            return v1(HttpStatus.PAYLOAD_TOO_LARGE, ErrorCode.PAYLOAD_TOO_LARGE, UPLOAD_TOO_LARGE, null);
        }
        return flat(HttpStatus.PAYLOAD_TOO_LARGE, UPLOAD_TOO_LARGE);
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<?> handleInvalidUpload(MultipartException ex, HttpServletRequest request) {
        clientError(request, ErrorCode.INVALID_REQUEST, "Invalid multipart upload: " + forLog(ex.getMessage()));
        if (isV1(request)) {
            return v1(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST, "Invalid photo upload: " + ex.getMessage(), null);
        }
        return flat(HttpStatus.BAD_REQUEST, "Invalid photo upload");
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<?> handleMissingPart(MissingServletRequestPartException ex, HttpServletRequest request) {
        String message = "Required multipart part '" + ex.getRequestPartName() + "' is missing";
        clientError(request, ErrorCode.NO_PHOTOS, forLog(message));
        if (isV1(request)) {
            return v1(HttpStatus.BAD_REQUEST, ErrorCode.NO_PHOTOS, message, null);
        }
        return flat(HttpStatus.BAD_REQUEST, "Invalid request (HTTP 400)");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<?> handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        String message = "Invalid value for parameter '" + ex.getName() + "': " + ex.getValue();
        clientError(request, ErrorCode.INVALID_REQUEST, forLog(message));
        if (isV1(request)) {
            return v1(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST, message, null);
        }
        return flat(HttpStatus.BAD_REQUEST, "Invalid request (HTTP 400)");
    }

    /**
     * A JSON body that is missing or does not parse (ratings, pets and job-pet requests): 400 INVALID_REQUEST.
     * Outside v1 nothing reads a JSON body; those requests keep the frozen answer of {@link #handleAny}.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> handleUnreadableBody(HttpMessageNotReadableException ex, HttpServletRequest request,
                                                  HttpServletResponse response) {
        if (!isV1(request)) {
            return handleAny(ex, request, response);
        }
        clientError(request, ErrorCode.INVALID_REQUEST, "Unreadable request body: " + forLog(ex.getMessage()));
        return v1(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST, "The request body is missing or is not valid JSON", null);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
        clientError(request, ErrorCode.INVALID_REQUEST, "Bad request: " + forLog(ex.getMessage()));
        if (isV1(request)) {
            return v1(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST, ex.getMessage(), null);
        }
        return flat(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleAny(Exception ex, HttpServletRequest request, HttpServletResponse response) {
        // The client left while the response was being written (a phone leaving the GLB download): the response is
        // committed and nobody is listening, so there is nothing to answer and no server fault to log as an ERROR.
        // AssetDownloadMetricsFilter counts these downloads as client_abort. Null = write nothing more. Only when
        // something was committed: on an uncommitted response null would leave an empty 200, so it gets the 500 below.
        if (isClientGone(ex) && response != null && response.isCommitted()) {
            log.debug("{} {}: the client went away ({})", method(request), path(request), ex.getClass().getSimpleName());
            return null;
        }
        // Preserve framework HTTP errors (e.g. wrong method / media type), not 500.
        if (ex instanceof org.springframework.web.ErrorResponse error) {
            HttpStatusCode status = error.getStatusCode();
            int value = status.value();
            String detail = ex.getClass().getSimpleName() + ": " + forLog(ex.getMessage());
            if (value >= 500) {
                logServerError(request, FRAMEWORK, value, ex);
            } else if (value == 404) {
                log.debug(CLIENT_LINE, method(request), path(request), FRAMEWORK, value, detail);
            } else {
                log.warn(CLIENT_LINE, method(request), path(request), FRAMEWORK, value, detail);
            }
            count(value, FRAMEWORK);
            String message = "Invalid request (HTTP " + value + ")";
            if (isV1(request)) {
                ErrorCode code = value == 404 ? ErrorCode.NOT_FOUND
                        : status.is4xxClientError() ? ErrorCode.INVALID_REQUEST
                        : ErrorCode.INTERNAL_ERROR;
                return ResponseEntity.status(status).headers(error.getHeaders())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(new ErrorResponse(new ErrorDetail(code.name(), message, null)));
            }
            return ResponseEntity.status(status).headers(error.getHeaders()).body(Map.of("error", message));
        }
        logServerError(request, ErrorCode.INTERNAL_ERROR.name(), 500, ex);
        count(500, ErrorCode.INTERNAL_ERROR.name());
        if (isV1(request)) {
            return v1(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR, "Internal server error", null);
        }
        return flat(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");
    }

    /**
     * A request path for a log line: {@link #forLog} plus a share-link token cut to its first 6 characters, as
     * ShareService logs it. The token is the link's only secret, and openapi (revokeJobShares) says the server logs
     * keep only those 6; a SHARE_NOT_FOUND warning used to log the whole token with the path (review finding,
     * 2026-11-25). deploy/Caddyfile cuts it the same way in the access log.
     */
    public static String pathForLog(String uri) {
        return uri == null ? null : forLog(SHARE_TOKEN_IN_PATH.matcher(uri).replaceFirst("$1$2..."));
    }

    /**
     * Messages that carry request text (a {jobId} path variable is URL-decoded, a client filename) are logged with
     * control characters escaped, so CR/LF cannot forge log lines and escape sequences cannot rewrite a terminal.
     */
    static String forLog(String text) {
        if (text == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\r' -> out.append("\\r");
                case '\n' -> out.append("\\n");
                case '\t' -> out.append("\\t");
                default -> {
                    if (Character.isISOControl(c)) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    /**
     * Whether {@code ex} says the peer closed the connection, by Spring's DisconnectedClientHelper rule: the exception
     * itself (not a cause) is Tomcat's ClientAbortException, an EOFException / Jetty EofException or Spring's
     * AsyncRequestNotUsableException (newer Spring versions), or the most specific cause's message is the OS's
     * "broken pipe" / "connection reset by peer". An EOF deeper in the chain is not enough: a server-side read that
     * hit the end of a file is a fault, not a phone that left.
     */
    static boolean isClientGone(Throwable ex) {
        String type = ex.getClass().getSimpleName();
        if (DISCONNECT_TYPE_NAMES.contains(type)) {
            return true;
        }
        String message = NestedExceptionUtils.getMostSpecificCause(ex).getMessage();
        if (message == null) {
            return false;
        }
        String text = message.toLowerCase(Locale.ROOT);
        return text.contains("broken pipe") || text.contains("connection reset by peer");
    }

    /** One WARN line for a client error answered with {@code code}'s own status; counted. */
    private void clientError(HttpServletRequest request, ErrorCode code, String detail) {
        int status = code.httpStatus().value();
        log.warn(CLIENT_LINE, method(request), path(request), code, status, detail);
        count(status, code.name());
    }

    private void logServerError(HttpServletRequest request, String code, int status, Throwable ex) {
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        log.error(SERVER_LINE, method(request), path(request), code, status, requestId == null ? "-" : requestId, ex);
    }

    /** beside.api.errors{status, code}: bounded, the HTTP error codes of ErrorCode plus FRAMEWORK. */
    private void count(int status, String code) {
        counter(status, code).increment();
    }

    private Counter counter(int status, String code) {
        return errors.computeIfAbsent(status + " " + code, key -> Counter.builder("beside.api.errors")
                .description("Error responses written by GlobalExceptionHandler, by HTTP status and error code")
                .tag("status", String.valueOf(status))
                .tag("code", code)
                .register(registry));
    }

    private static String method(HttpServletRequest request) {
        return request == null ? null : forLog(request.getMethod());
    }

    private static String path(HttpServletRequest request) {
        return request == null ? null : pathForLog(request.getRequestURI());
    }

    private static boolean isV1(HttpServletRequest request) {
        return request != null && request.getRequestURI() != null && request.getRequestURI().startsWith(V1_PREFIX);
    }

    private static ResponseEntity<ErrorResponse> v1(HttpStatus status, ErrorCode code, String message, String jobId) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorResponse(new ErrorDetail(code.name(), message == null ? "" : message, jobId)));
    }

    /** v0 shape, frozen: {@code { "error": "text" }}. HashMap tolerates a null message. */
    private static ResponseEntity<Map<String, String>> flat(HttpStatus status, String message) {
        Map<String, String> body = new HashMap<>();
        body.put("error", message);
        return ResponseEntity.status(status).body(body);
    }
}
