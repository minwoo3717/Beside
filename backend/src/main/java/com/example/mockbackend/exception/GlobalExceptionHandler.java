package com.example.mockbackend.exception;

import com.example.mockbackend.api.v1.dto.ErrorDetail;
import com.example.mockbackend.api.v1.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

/**
 * One handler, two response shapes:
 * <ul>
 *   <li>Requests under {@code /api/v1/} get the contract envelope {@code { error: { code, message, jobId? } }}.</li>
 *   <li>Everything else (v0 {@code /api/jobs}, static pages) keeps the frozen flat shape {@code { error: "text" }}.</li>
 * </ul>
 * The split is by request path, not by controller, because 413 (multipart limits) is raised before a
 * handler is mapped and a controller-scoped advice would not see it.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    static final String V1_PREFIX = "/api/v1/";
    private static final String UPLOAD_TOO_LARGE = "Upload too large: maximum 5 MB per photo and 20 MB per request";
    private static final Set<String> DISCONNECT_TYPE_NAMES =
            Set.of("ClientAbortException", "EOFException", "EofException", "AsyncRequestNotUsableException");

    private final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<?> handleApi(ApiException ex, HttpServletRequest request) {
        log.warn("{} {} {}: {}", request.getMethod(), request.getRequestURI(), ex.getCode(), forLog(ex.getMessage()));
        if (isV1(request)) {
            return v1(ex.getStatus(), ex.getCode(), ex.getMessage(), ex.getJobId());
        }
        return flat(ex.getStatus(), ex.getMessage());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<?> handleMissingResource(NoResourceFoundException ex, HttpServletRequest request) {
        if (isV1(request)) {
            return v1(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, "Resource not found: " + request.getRequestURI(), null);
        }
        return flat(HttpStatus.NOT_FOUND, "Resource not found");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<?> handleUploadTooLarge(MaxUploadSizeExceededException ex, HttpServletRequest request) {
        if (isV1(request)) {
            return v1(HttpStatus.PAYLOAD_TOO_LARGE, ErrorCode.PAYLOAD_TOO_LARGE, UPLOAD_TOO_LARGE, null);
        }
        return flat(HttpStatus.PAYLOAD_TOO_LARGE, UPLOAD_TOO_LARGE);
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<?> handleInvalidUpload(MultipartException ex, HttpServletRequest request) {
        log.warn("Invalid multipart upload: {}", forLog(ex.getMessage()));
        if (isV1(request)) {
            return v1(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST, "Invalid photo upload: " + ex.getMessage(), null);
        }
        return flat(HttpStatus.BAD_REQUEST, "Invalid photo upload");
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<?> handleMissingPart(MissingServletRequestPartException ex, HttpServletRequest request) {
        if (isV1(request)) {
            return v1(HttpStatus.BAD_REQUEST, ErrorCode.NO_PHOTOS,
                    "Required multipart part '" + ex.getRequestPartName() + "' is missing", null);
        }
        return flat(HttpStatus.BAD_REQUEST, "Invalid request (HTTP 400)");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<?> handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        if (isV1(request)) {
            return v1(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST,
                    "Invalid value for parameter '" + ex.getName() + "': " + ex.getValue(), null);
        }
        return flat(HttpStatus.BAD_REQUEST, "Invalid request (HTTP 400)");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
        log.warn("Bad request: {}", forLog(ex.getMessage()));
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
            log.debug("{} {}: the client went away ({})", request == null ? null : request.getMethod(),
                    request == null ? null : forLog(request.getRequestURI()), ex.getClass().getSimpleName());
            return null;
        }
        // Preserve framework HTTP errors (e.g. wrong method / media type), not 500.
        if (ex instanceof org.springframework.web.ErrorResponse error) {
            HttpStatusCode status = error.getStatusCode();
            String message = "Invalid request (HTTP " + status.value() + ")";
            if (isV1(request)) {
                ErrorCode code = status.value() == 404 ? ErrorCode.NOT_FOUND
                        : status.is4xxClientError() ? ErrorCode.INVALID_REQUEST
                        : ErrorCode.INTERNAL_ERROR;
                return ResponseEntity.status(status).headers(error.getHeaders())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(new ErrorResponse(new ErrorDetail(code.name(), message, null)));
            }
            return ResponseEntity.status(status).headers(error.getHeaders()).body(Map.of("error", message));
        }
        log.error("Internal error", ex);
        if (isV1(request)) {
            return v1(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR, "Internal server error", null);
        }
        return flat(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");
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
