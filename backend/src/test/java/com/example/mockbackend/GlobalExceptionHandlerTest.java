package com.example.mockbackend;

import com.example.mockbackend.api.v1.dto.ErrorResponse;
import com.example.mockbackend.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 413 is raised while parsing multipart, before any controller is mapped, so MockMvc cannot trigger it
 * through the servlet limits. Exercise the handler directly for both response shapes.
 */
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
}
