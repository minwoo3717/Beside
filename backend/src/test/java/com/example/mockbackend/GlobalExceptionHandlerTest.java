package com.example.mockbackend;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.mockbackend.api.v1.dto.ErrorResponse;
import com.example.mockbackend.config.RequestIdFilter;
import com.example.mockbackend.exception.ApiException;
import com.example.mockbackend.exception.ErrorCode;
import com.example.mockbackend.exception.GlobalExceptionHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.catalina.connector.ClientAbortException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.EOFException;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 413 is raised while parsing multipart, before any controller is mapped, so MockMvc cannot trigger it
 * through the servlet limits. Exercise the handler directly for both response shapes.
 */
@ExtendWith(OutputCaptureExtension.class)
class GlobalExceptionHandlerTest {
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(meters);
    private final Logger handlerLog = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private Level levelBefore;

    @BeforeEach
    void captureHandlerLog() {
        levelBefore = handlerLog.getLevel();
        handlerLog.setLevel(Level.DEBUG);
        logged.start();
        handlerLog.addAppender(logged);
    }

    @AfterEach
    void restoreHandlerLog() {
        handlerLog.detachAppender(logged);
        handlerLog.setLevel(levelBefore);
        MDC.remove(RequestIdFilter.MDC_KEY);
    }

    @Test
    void uploadTooLargeUsesContractEnvelopeOnV1AndFlatShapeOnV0() {
        ResponseEntity<?> v1 = handler.handleUploadTooLarge(new MaxUploadSizeExceededException(5L),
                new MockHttpServletRequest("POST", "/api/v1/jobs"));
        assertThat(v1.getStatusCode().value()).isEqualTo(413);
        assertThat(v1.getBody()).isInstanceOf(ErrorResponse.class);
        ErrorResponse body = (ErrorResponse) v1.getBody();
        assertThat(body.error().code()).isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(body.error().message()).contains("5 MB").contains("20 MB");
        assertThat(body.error().jobId()).isNull();

        ResponseEntity<?> v0 = handler.handleUploadTooLarge(new MaxUploadSizeExceededException(5L),
                new MockHttpServletRequest("POST", "/api/jobs"));
        assertThat(v0.getStatusCode().value()).isEqualTo(413);
        assertThat(v0.getBody()).isEqualTo(Map.of("error", "Upload too large: maximum 5 MB per photo and 20 MB per request"));
    }

    @Test
    void queueFullCarriesRetryAfterOnV1OnlyAndIsLoggedByTheGateNotHere() {
        // REVIEW_CHECKLIST #21 (decided 2026-11-21): 503 + Retry-After, jobId of a refused retry.
        ResponseEntity<?> retry = handler.handleApi(ApiException.queueFull("job-1", 45),
                new MockHttpServletRequest("POST", "/api/v1/jobs/job-1/retry"));
        assertThat(retry.getStatusCode().value()).isEqualTo(503);
        assertThat(retry.getHeaders().getFirst("Retry-After")).isEqualTo("45");
        assertThat(retry.getHeaders().getContentType()).hasToString("application/json");
        ErrorResponse body = (ErrorResponse) retry.getBody();
        assertThat(body.error().code()).isEqualTo("QUEUE_FULL");
        assertThat(body.error().jobId()).isEqualTo("job-1");

        ResponseEntity<?> create = handler.handleApi(ApiException.queueFull(null, 45),
                new MockHttpServletRequest("POST", "/api/v1/jobs"));
        assertThat(((ErrorResponse) create.getBody()).error().jobId()).isNull();

        // Outside /api/v1/ the frozen flat shape, without the new header.
        ResponseEntity<?> v0 = handler.handleApi(ApiException.queueFull(null, 45),
                new MockHttpServletRequest("POST", "/api/jobs"));
        assertThat(v0.getStatusCode().value()).isEqualTo(503);
        assertThat(v0.getHeaders().containsKey("Retry-After")).isFalse();
        assertThat(v0.getBody()).isInstanceOf(Map.class);

        // Other API errors keep their WARN line and get no Retry-After.
        ResponseEntity<?> notFound = handler.handleApi(ApiException.jobNotFound("x"),
                new MockHttpServletRequest("GET", "/api/v1/jobs/x"));
        assertThat(notFound.getHeaders().containsKey("Retry-After")).isFalse();
        assertThat(logged.list).extracting(ILoggingEvent::getLevel)
                .containsExactly(Level.DEBUG, Level.DEBUG, Level.DEBUG, Level.WARN);
    }

    @Test
    void aClientThatWentAwayIsNoServerError(CapturedOutput output) {
        // A phone that leaves the screen mid-download: Tomcat reports ClientAbortException while the GLB is written.
        // The response is already committed (200, GLB headers), so there is nothing left to answer and nothing to page
        // anyone about; AssetDownloadMetricsFilter counts it as client_abort.
        MockHttpServletRequest asset = new MockHttpServletRequest("GET", "/api/v1/jobs/x/asset");
        assertThat(handler.handleAny(new ClientAbortException(new IOException("Connection reset by peer")), asset,
                committed())).isNull();
        assertThat(handler.handleAny(new IOException("Broken pipe"), asset, committed())).isNull();
        assertThat(handler.handleAny(new EOFException(), asset, committed())).isNull();
        // Spring's rule (DisconnectedClientHelper): the OS text is read from the most specific cause.
        assertThat(handler.handleAny(new IllegalStateException("write failed", new IOException("Connection reset by peer")),
                asset, committed())).isNull();
        assertThat(logged.list).hasSize(4).allSatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
            assertThat(event.getFormattedMessage()).contains("/api/v1/jobs/x/asset").contains("the client went away");
        });
        // (Only this handler's lines: cached contexts of other test classes may log on their own threads meanwhile.)
        assertThat(output.getOut()).doesNotContain("Internal error").doesNotContain("Connection reset by peer")
                .doesNotContain("Broken pipe");
    }

    @Test
    void aDisconnectSignalIsAServerErrorWhileNothingWasCommitted() {
        // Nothing has reached the client yet: answering nothing would leave an empty 200, so the normal 500 is written.
        MockHttpServletRequest asset = new MockHttpServletRequest("GET", "/api/v1/jobs/x/asset");
        assertInternalError(handler.handleAny(new IOException("write failed", new EOFException()), asset,
                new MockHttpServletResponse()));
        assertInternalError(handler.handleAny(new ClientAbortException(new IOException("Connection reset by peer")), asset,
                new MockHttpServletResponse()));
        assertInternalError(handler.handleAny(new EOFException(), asset, null));
        // Was the bare text "Internal error" until 2026-11-26; the line now names the request (mentoring: log all errors).
        assertThat(logged.list).hasSize(3).allSatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage())
                    .isEqualTo("Internal error: GET /api/v1/jobs/x/asset INTERNAL_ERROR (HTTP 500, requestId=-)");
            assertThat(event.getThrowableProxy()).as("the stack").isNotNull();
        });
    }

    @Test
    void everyServerErrorIsOneErrorLineWithMethodPathRequestIdAndTheStack() {
        MDC.put(RequestIdFilter.MDC_KEY, "unity-req-7");
        String token = "AbCdEfGhIjKlMnOpQrStUv";
        assertInternalError(handler.handleAny(new IllegalStateException("boom"),
                new MockHttpServletRequest("GET", "/api/v1/shares/" + token + "/asset"), new MockHttpServletResponse()));
        // v0 and web pages too (frozen flat body), including a body that does not parse outside v1.
        ResponseEntity<?> v0 = handler.handleAny(new IllegalStateException("v0 boom"),
                new MockHttpServletRequest("POST", "/api/jobs"), new MockHttpServletResponse());
        assertThat(v0.getStatusCode().value()).isEqualTo(500);
        ResponseEntity<?> unreadable = handler.handleUnreadableBody(new HttpMessageNotReadableException("bad", (HttpInputMessage) null),
                new MockHttpServletRequest("POST", "/api/jobs"), new MockHttpServletResponse());
        assertThat(unreadable.getStatusCode().value()).isEqualTo(500);
        // A framework error that is the server's (503 of an async timeout) is an ERROR as well.
        ResponseEntity<?> timeout = handler.handleAny(new AsyncRequestTimeoutException(),
                new MockHttpServletRequest("GET", "/api/v1/jobs"), new MockHttpServletResponse());
        assertThat(timeout.getStatusCode().value()).isEqualTo(503);

        assertThat(logged.list).extracting(ILoggingEvent::getFormattedMessage).containsExactly(
                "Internal error: GET /api/v1/shares/AbCdEf.../asset INTERNAL_ERROR (HTTP 500, requestId=unity-req-7)",
                "Internal error: POST /api/jobs INTERNAL_ERROR (HTTP 500, requestId=unity-req-7)",
                "Internal error: POST /api/jobs INTERNAL_ERROR (HTTP 500, requestId=unity-req-7)",
                "Internal error: GET /api/v1/jobs FRAMEWORK (HTTP 503, requestId=unity-req-7)");
        assertThat(logged.list).allSatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getThrowableProxy()).as("the stack").isNotNull();
        });
        assertThat(logged.list.get(0).getThrowableProxy().getMessage()).isEqualTo("boom");
    }

    @Test
    void clientErrorsThatUsedToBeSilentAreOneWarnLineWithoutAStack() {
        handler.handleUploadTooLarge(new MaxUploadSizeExceededException(5L), new MockHttpServletRequest("POST", "/api/v1/jobs"));
        handler.handleMissingPart(new MissingServletRequestPartException("photos"),
                new MockHttpServletRequest("POST", "/api/v1/jobs"));
        handler.handleTypeMismatch(new MethodArgumentTypeMismatchException("abc", Integer.class, "limit", null, null),
                new MockHttpServletRequest("GET", "/api/v1/jobs"));
        // Spring's own 4xx: wrong method, wrong media type (body still INVALID_REQUEST on v1, counted as FRAMEWORK).
        ResponseEntity<?> method = handler.handleAny(new HttpRequestMethodNotSupportedException("PUT"),
                new MockHttpServletRequest("PUT", "/api/v1/jobs"), new MockHttpServletResponse());
        assertThat(((ErrorResponse) method.getBody()).error().code()).isEqualTo("INVALID_REQUEST");
        handler.handleAny(new HttpMediaTypeNotSupportedException("text/plain\r\nforged"),
                new MockHttpServletRequest("POST", "/api/v1/pets"), new MockHttpServletResponse());

        assertThat(logged.list).extracting(ILoggingEvent::getFormattedMessage).containsExactly(
                "POST /api/v1/jobs PAYLOAD_TOO_LARGE (HTTP 413): Maximum upload size of 5 bytes exceeded",
                "POST /api/v1/jobs NO_PHOTOS (HTTP 400): Required multipart part 'photos' is missing",
                "GET /api/v1/jobs INVALID_REQUEST (HTTP 400): Invalid value for parameter 'limit': abc",
                "PUT /api/v1/jobs FRAMEWORK (HTTP 405): HttpRequestMethodNotSupportedException: Request method 'PUT' is not supported",
                "POST /api/v1/pets FRAMEWORK (HTTP 415): HttpMediaTypeNotSupportedException: text/plain\\r\\nforged");
        assertThat(logged.list).allSatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    @Test
    void unknownRoutesStayAtDebugSoScannersDoNotFillTheLogButUnknownV1RoutesWarn() {
        ResponseEntity<?> page = handler.handleMissingResource(new NoResourceFoundException(HttpMethod.GET, "wp-login.php"),
                new MockHttpServletRequest("GET", "/wp-login.php"));
        assertThat(page.getStatusCode().value()).isEqualTo(404);
        handler.handleMissingResource(new NoResourceFoundException(HttpMethod.GET, "api/v1/nope"),
                new MockHttpServletRequest("GET", "/api/v1/nope"));
        handler.handleAny(new NoHandlerFoundException("GET", "/api/v1/none", new HttpHeaders()),
                new MockHttpServletRequest("GET", "/api/v1/none"), new MockHttpServletResponse());

        // Scanners hit the root paths: DEBUG. A route missing under /api/v1/ is a real client (an app built for a newer
        // contract, a wrong base URL), so it is WARN in both the resource and the framework path.
        assertThat(logged.list).extracting(ILoggingEvent::getLevel).containsExactly(Level.DEBUG, Level.WARN, Level.WARN);
        assertThat(logged.list.get(0).getFormattedMessage()).isEqualTo("GET /wp-login.php NOT_FOUND (HTTP 404): no such route");
        assertThat(logged.list.get(1).getFormattedMessage()).isEqualTo("GET /api/v1/nope NOT_FOUND (HTTP 404): no such route");
    }

    @Test
    void everyAnsweredErrorIsCountedByStatusAndCode() {
        // Every HTTP error code of ErrorCode exists at 0 from the start, with its own status.
        long httpCodes = Arrays.stream(ErrorCode.values()).filter(code -> code.httpStatus() != null).count();
        assertThat(meters.find("beside.api.errors").counters()).hasSize((int) httpCodes)
                .allSatisfy(counter -> assertThat(counter.count()).isZero());
        assertThat(errors("404", "JOB_NOT_FOUND")).isZero();
        assertThat(errors("503", "QUEUE_FULL")).isZero();

        handler.handleApi(ApiException.jobNotFound("x"), new MockHttpServletRequest("GET", "/api/v1/jobs/x"));
        handler.handleApi(ApiException.jobNotFound("y"), new MockHttpServletRequest("GET", "/api/jobs/y")); // v0: same code
        handler.handleApi(ApiException.queueFull(null, 45), new MockHttpServletRequest("POST", "/api/v1/jobs"));
        handler.handleAny(new IllegalStateException("boom"), new MockHttpServletRequest("GET", "/api/v1/jobs"),
                new MockHttpServletResponse());
        handler.handleMissingResource(new NoResourceFoundException(HttpMethod.GET, "x"), new MockHttpServletRequest("GET", "/x"));
        handler.handleAny(new HttpRequestMethodNotSupportedException("PUT"), new MockHttpServletRequest("PUT", "/api/v1/jobs"),
                new MockHttpServletResponse());
        // A client that left mid-download got no answer: not counted.
        handler.handleAny(new ClientAbortException(), new MockHttpServletRequest("GET", "/api/v1/jobs/x/asset"), committed());

        assertThat(errors("404", "JOB_NOT_FOUND")).isEqualTo(2);
        assertThat(errors("503", "QUEUE_FULL")).isEqualTo(1);
        assertThat(errors("500", "INTERNAL_ERROR")).isEqualTo(1);
        assertThat(errors("404", "NOT_FOUND")).isEqualTo(1);
        assertThat(errors("405", "FRAMEWORK")).isEqualTo(1);
        assertThat(meters.find("beside.api.errors").counters()).hasSize((int) httpCodes + 1);
    }

    private double errors(String status, String code) {
        return meters.get("beside.api.errors").tag("status", status).tag("code", code).counter().count();
    }

    @Test
    void anEofDeepInTheCauseChainIsNoDisconnectEvenWhenCommitted() {
        // Only the top-level type counts (as in Spring): an EOF somewhere below a server-side failure is a fault.
        assertInternalError(handler.handleAny(new IOException("write failed", new EOFException()),
                new MockHttpServletRequest("GET", "/api/v1/jobs/x/asset"), committed()));
        assertThat(logged.list).singleElement().satisfies(event -> assertThat(event.getLevel()).isEqualTo(Level.ERROR));
    }

    @Test
    void anyOtherIOExceptionIsStillAnInternalError(CapturedOutput output) {
        assertInternalError(handler.handleAny(new IOException("disk read failed"),
                new MockHttpServletRequest("GET", "/api/v1/jobs/x/asset"), committed()));
        assertThat(output.getOut()).contains("Internal error").contains("disk read failed");
    }

    @Test
    void requestTextInALogLineCannotForgeLinesOrDriveTheTerminal(CapturedOutput output) {
        String jobId = "x\r\n2026-10-31T13:00:00.000Z  INFO 1 --- forged\u001b[2J";
        handler.handleApi(ApiException.jobNotFound(jobId), new MockHttpServletRequest("GET", "/api/v1/jobs/x"));
        // The escaped text is read from the event, not the console: since logback-spring.xml (2026-11-26) the console
        // of this test JVM is JSON when a prod-profile context initialised logging first, and JSON escapes the
        // backslashes once more. Both formats must keep the line whole.
        assertThat(logged.list).singleElement().extracting(ILoggingEvent::getFormattedMessage).asString()
                .contains("x\\r\\n2026-10-31T13:00:00.000Z  INFO 1 --- forged\\u001b[2J");
        assertThat(output.getOut()).contains("2026-10-31T13:00:00.000Z  INFO 1 --- forged")
                .doesNotContain("\n2026-10-31T13:00:00.000Z  INFO").doesNotContain("\u001b");
    }

    /** Review finding 2026-11-25: openapi says the server logs keep only a share token's first 6 characters. */
    @Test
    void aShareTokenInTheLoggedPathIsCutToItsFirstSixCharacters(CapturedOutput output) {
        String token = "AbCdEfGhIjKlMnOpQrStUv";
        ApiException notFound = new ApiException(org.springframework.http.HttpStatus.NOT_FOUND,
                com.example.mockbackend.exception.ErrorCode.SHARE_NOT_FOUND, "Share link not found or expired", null);
        handler.handleApi(notFound, new MockHttpServletRequest("GET", "/api/v1/shares/" + token + "/asset"));
        handler.handleApi(notFound, new MockHttpServletRequest("GET", "/api/v1/shares/" + token));
        assertThat(output.getOut()).contains("/api/v1/shares/AbCdEf.../asset SHARE_NOT_FOUND")
                .contains("/api/v1/shares/AbCdEf... SHARE_NOT_FOUND").doesNotContain(token);

        assertThat(GlobalExceptionHandler.pathForLog("/s/" + token)).isEqualTo("/s/AbCdEf...");
        assertThat(GlobalExceptionHandler.pathForLog("//API/v1/shares/" + token)).isEqualTo("//API/v1/shares/AbCdEf...");
        assertThat(GlobalExceptionHandler.pathForLog("/api/v1/jobs/" + token + "/shares"))
                .as("only share-link paths").isEqualTo("/api/v1/jobs/" + token + "/shares");
        assertThat(GlobalExceptionHandler.pathForLog("/s/AbCdE")).as("too short to be a token: kept").isEqualTo("/s/AbCdE");
    }

    private static MockHttpServletResponse committed() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setCommitted(true);
        return response;
    }

    private static void assertInternalError(ResponseEntity<?> response) {
        assertThat(response).isNotNull();
        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(((ErrorResponse) response.getBody()).error().code()).isEqualTo("INTERNAL_ERROR");
    }
}
