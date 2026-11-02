package com.example.mockbackend;

import com.example.mockbackend.config.AssetDownloadMetricsFilter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.apache.catalina.connector.ClientAbortException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.io.OutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * beside.asset.downloads{outcome} (docs/METRICS.md §5) from the bytes the server actually handed to the connection:
 * a GLB that stops early (the phone left the screen, the network dropped) is a client_abort even when no exception
 * reached Spring; 404 / 409 / 5xx are told apart; other requests are not counted.
 */
class AssetDownloadMetricsFilterTest {
    private static final String ASSET = "/api/v1/jobs/0f8fad5b-d9cb-469f-a165-70867728950e/asset";

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AssetDownloadMetricsFilter filter = new AssetDownloadMetricsFilter(registry);

    @Test
    void everyOutcomeIsRegisteredAtZero() {
        for (String outcome : new String[]{"ok", "client_abort", "error", "not_found", "not_completed"}) {
            assertThat(registry.get("beside.asset.downloads").tag("outcome", outcome).counter().count()).isZero();
        }
    }

    @Test
    void aFullBodyIsOk() throws Exception {
        run(new MockHttpServletResponse(), body(1000, 1000));
        assertThat(count("ok")).isEqualTo(1.0);
        assertThat(registry.get("beside.asset.download").tag("outcome", "ok").timer().count()).isEqualTo(1);
    }

    @Test
    void anEmptyGlbWithContentLengthZeroIsOk() throws Exception {
        run(new MockHttpServletResponse(), body(0, 0)); // storage/results/sample-dog.glb is 0 bytes today
        assertThat(count("ok")).isEqualTo(1.0);
    }

    @Test
    void fewerBytesThanContentLengthIsAClientAbort() throws Exception {
        run(new MockHttpServletResponse(), body(1000, 400));
        assertThat(count("client_abort")).isEqualTo(1.0);
        assertThat(count("ok")).isZero();
    }

    @Test
    void contentLengthSetAsAHeaderCountsToo() throws Exception {
        // Spring's ServletServerHttpResponse adds every header with addHeader, Content-Length included.
        run(new MockHttpServletResponse(), (request, response) -> {
            HttpServletResponse http = (HttpServletResponse) response;
            http.setStatus(200);
            http.addHeader("Content-Length", "10");
            http.getOutputStream().write(new byte[3]);
        });
        assertThat(count("client_abort")).isEqualTo(1.0);
    }

    @Test
    void aConnectionThatBreaksWhileWritingIsAClientAbortAndTheErrorStillPropagates() {
        MockHttpServletResponse breaking = new BreakingResponse(512);
        assertThatThrownBy(() -> run(breaking, body(4096, 4096))).isInstanceOf(ClientAbortException.class);
        assertThat(count("client_abort")).isEqualTo(1.0);
        assertThat(count("error")).isZero();
    }

    @Test
    void aBrokenFinalFlushIsAClientAbort() throws Exception {
        HttpServletResponse breakingFlush = new HttpServletResponseWrapper(new MockHttpServletResponse()) {
            @Override
            public void flushBuffer() throws IOException {
                throw new ClientAbortException("flush after the peer left");
            }
        };
        assertThatThrownBy(() -> run(breakingFlush, (request, response) -> {
            body(8, 8).doFilter(request, response);
            response.flushBuffer();
        })).isInstanceOf(ClientAbortException.class);
        assertThat(count("client_abort")).isEqualTo(1.0);
    }

    @Test
    void notFoundNotCompletedAndServerErrorsAreToldApart() throws Exception {
        run(new MockHttpServletResponse(), status(404)); // JOB_NOT_FOUND / ASSET_NOT_FOUND
        run(new MockHttpServletResponse(), status(409)); // JOB_NOT_COMPLETED
        run(new MockHttpServletResponse(), status(500));
        run(new MockHttpServletResponse(), status(400)); // bad variant: not one of the expected answers
        assertThat(count("not_found")).isEqualTo(1.0);
        assertThat(count("not_completed")).isEqualTo(1.0);
        assertThat(count("error")).isEqualTo(2.0);
    }

    @Test
    void anExceptionOutOfTheChainIsAnError() {
        assertThatThrownBy(() -> run(new MockHttpServletResponse(), (request, response) -> {
            throw new IllegalStateException("disk gone");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(count("error")).isEqualTo(1.0);
    }

    @Test
    void otherRequestsAreNotCounted() throws Exception {
        for (String[] request : new String[][]{{"GET", "/api/v1/jobs/abc"}, {"POST", ASSET}, {"GET", ASSET + "/x"},
                {"GET", "/api/jobs/abc/result"}, {"HEAD", ASSET}}) {
            MockHttpServletRequest other = new MockHttpServletRequest(request[0], request[1]);
            filter.doFilter(other, new MockHttpServletResponse(), body(10, 10));
        }
        assertThat(registry.find("beside.asset.downloads").counters()).allSatisfy(c -> assertThat(c.count()).isZero());
    }

    private void run(HttpServletResponse response, FilterChain chain) throws Exception {
        filter.doFilter(new MockHttpServletRequest("GET", ASSET), response, chain);
    }

    private double count(String outcome) {
        Counter counter = registry.get("beside.asset.downloads").tag("outcome", outcome).counter();
        return counter.count();
    }

    /** What the controller does for a GLB: 200, Content-Length, then the bytes (here possibly fewer). */
    private static FilterChain body(long declared, int written) {
        return (request, response) -> {
            HttpServletResponse http = (HttpServletResponse) response;
            http.setStatus(200);
            http.setContentType("model/gltf-binary");
            http.setContentLengthLong(declared);
            OutputStream out = http.getOutputStream();
            byte[] chunk = new byte[256];
            for (int left = written; left > 0; left -= chunk.length) {
                out.write(chunk, 0, Math.min(chunk.length, left));
            }
            out.flush();
        };
    }

    private static FilterChain status(int code) {
        return (request, response) -> {
            HttpServletResponse http = (HttpServletResponse) response;
            http.setStatus(code);
            http.setContentType("application/json");
            http.getOutputStream().write("{\"error\":{}}".getBytes());
        };
    }

    /** A connection whose peer goes away after {@code limit} bytes, as Tomcat reports it. */
    private static final class BreakingResponse extends MockHttpServletResponse {
        private final int limit;
        private int sent;

        BreakingResponse(int limit) {
            this.limit = limit;
        }

        @Override
        public ServletOutputStream getOutputStream() {
            return new ServletOutputStream() {
                @Override
                public void write(int b) throws IOException {
                    if (++sent > limit) {
                        throw new ClientAbortException("connection reset by peer");
                    }
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setWriteListener(WriteListener listener) {
                }
            };
        }
    }
}
