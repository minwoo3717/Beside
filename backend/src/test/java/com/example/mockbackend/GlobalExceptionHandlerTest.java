package com.example.mockbackend;

import com.example.mockbackend.api.v1.dto.ErrorResponse;
import com.example.mockbackend.exception.ApiException;
import com.example.mockbackend.exception.GlobalExceptionHandler;
import org.apache.catalina.connector.ClientAbortException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
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
        assertThat(handler.handleAny(new ClientAbortException(new IOException("Connection reset by peer")), asset)).isNull();
        assertThat(handler.handleAny(new IOException("Broken pipe"), asset)).isNull();
        assertThat(handler.handleAny(new IOException("write failed", new EOFException()), asset)).isNull();
        // (Only this handler's lines: cached contexts of other test classes may log on their own threads meanwhile.)
        assertThat(output.getOut()).doesNotContain("Internal error").doesNotContain("Connection reset by peer")
                .doesNotContain("Broken pipe");
    }

    @Test
    void anyOtherIOExceptionIsStillAnInternalError(CapturedOutput output) {
        ResponseEntity<?> response = handler.handleAny(new IOException("disk read failed"),
                new MockHttpServletRequest("GET", "/api/v1/jobs/x/asset"));
        assertThat(response).isNotNull();
        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(((ErrorResponse) response.getBody()).error().code()).isEqualTo("INTERNAL_ERROR");
        assertThat(output.getOut()).contains("Internal error").contains("disk read failed");
    }

    @Test
    void requestTextInALogLineCannotForgeLinesOrDriveTheTerminal(CapturedOutput output) {
        String jobId = "x\r\n2026-10-31T13:00:00.000Z  INFO 1 --- forged\u001b[2J";
        handler.handleApi(ApiException.jobNotFound(jobId), new MockHttpServletRequest("GET", "/api/v1/jobs/x"));
        assertThat(output.getOut()).contains("x\\r\\n2026-10-31T13:00:00.000Z  INFO 1 --- forged\\u001b[2J")
                .doesNotContain("\n2026-10-31T13:00:00.000Z  INFO").doesNotContain("\u001b");
    }
}
