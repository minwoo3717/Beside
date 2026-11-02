package com.example.mockbackend;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.mockbackend.api.v1.dto.ErrorResponse;
import com.example.mockbackend.exception.ApiException;
import com.example.mockbackend.exception.GlobalExceptionHandler;
import org.apache.catalina.connector.ClientAbortException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.io.EOFException;
import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 413 is raised while parsing multipart, before any controller is mapped, so MockMvc cannot trigger it
 * through the servlet limits. Exercise the handler directly for both response shapes.
 */
@ExtendWith(OutputCaptureExtension.class)
class GlobalExceptionHandlerTest {
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
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
        assertThat(logged.list).hasSize(3).allSatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage()).isEqualTo("Internal error");
        });
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
        assertThat(output.getOut()).contains("x\\r\\n2026-10-31T13:00:00.000Z  INFO 1 --- forged\\u001b[2J")
                .doesNotContain("\n2026-10-31T13:00:00.000Z  INFO").doesNotContain("\u001b");
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
