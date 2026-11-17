package com.example.mockbackend.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Asset download outcomes (docs/METRICS.md §5) for GET /api/v1/jobs/{id}/asset only: counter
 * {@code beside.asset.downloads} and timer {@code beside.asset.download}, both tagged outcome =
 * <ul>
 *   <li>{@code ok}: 2xx and every declared byte handed to the connection;</li>
 *   <li>{@code client_abort}: writing failed (the app or the network went away) or fewer bytes than Content-Length;</li>
 *   <li>{@code not_found}: 404 (JOB_NOT_FOUND, ASSET_NOT_FOUND); {@code not_completed}: 409 (JOB_NOT_COMPLETED);</li>
 *   <li>{@code error}: 5xx, an exception out of the chain, or any other status (400 bad variant).</li>
 * </ul>
 * Bytes are counted where the server hands them to Tomcat, so a client that leaves after the last bytes were already
 * buffered is still ok. The timer runs from the request to the last byte written.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AssetDownloadMetricsFilter extends OncePerRequestFilter {
    static final Pattern ASSET = Pattern.compile("^/api/v1/jobs/[^/]+/asset$");
    static final List<String> OUTCOMES = List.of("ok", "client_abort", "error", "not_found", "not_completed");
    /** Download-time buckets; the load-test criteria are 3 s (p95) and 6 s (p99) for a 1.5 MB GLB. */
    static final Duration[] DOWNLOAD_SLO = {Duration.ofMillis(100), Duration.ofMillis(250), Duration.ofMillis(500),
            Duration.ofSeconds(1), Duration.ofMillis(2500), Duration.ofSeconds(3), Duration.ofSeconds(5),
            Duration.ofSeconds(6), Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(60)};

    private final Map<String, Counter> downloads = new HashMap<>();
    private final Map<String, Timer> durations = new HashMap<>();

    public AssetDownloadMetricsFilter(MeterRegistry registry) {
        // Every outcome exists from startup at 0, so a failure rate is defined before the first failure.
        for (String outcome : OUTCOMES) {
            downloads.put(outcome, Counter.builder("beside.asset.downloads")
                    .description("GET /api/v1/jobs/{id}/asset by outcome")
                    .tag("outcome", outcome)
                    .register(registry));
            durations.put(outcome, Timer.builder("beside.asset.download")
                    .description("GET /api/v1/jobs/{id}/asset, request to last byte written")
                    .tag("outcome", outcome)
                    .serviceLevelObjectives(DOWNLOAD_SLO)
                    .register(registry));
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !"GET".equals(request.getMethod()) || uri == null || !ASSET.matcher(uri).matches();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        CountingResponse counting = new CountingResponse(response);
        boolean failed = false;
        try {
            chain.doFilter(request, counting);
        } catch (IOException | ServletException | RuntimeException | Error e) {
            failed = true;
            throw e;
        } finally {
            String outcome = outcome(counting.getStatus(), counting.declared, counting.written, counting.aborted, failed);
            downloads.get(outcome).increment();
            durations.get(outcome).record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    static String outcome(int status, long declared, long written, boolean aborted, boolean failed) {
        if (aborted) {
            return "client_abort";
        }
        if (failed) {
            return "error";
        }
        if (status >= 200 && status < 300) {
            return declared >= 0 && written < declared ? "client_abort" : "ok";
        }
        if (status == 404) {
            return "not_found";
        }
        if (status == 409) {
            return "not_completed";
        }
        return "error";
    }

    /** Remembers the declared Content-Length and counts the bytes that reached the connection's output stream. */
    private static final class CountingResponse extends HttpServletResponseWrapper {
        long declared = -1;
        long written;
        boolean aborted;
        private ServletOutputStream stream;

        CountingResponse(HttpServletResponse response) {
            super(response);
        }

        @Override
        public ServletOutputStream getOutputStream() throws IOException {
            if (stream == null) {
                stream = new CountingStream(super.getOutputStream());
            }
            return stream;
        }

        @Override
        public void flushBuffer() throws IOException {
            try {
                super.flushBuffer();
            } catch (IOException e) {
                aborted = true;
                throw e;
            }
        }

        @Override
        public void setContentLength(int len) {
            declared = len;
            super.setContentLength(len);
        }

        @Override
        public void setContentLengthLong(long len) {
            declared = len;
            super.setContentLengthLong(len);
        }

        @Override
        public void setHeader(String name, String value) {
            noteLength(name, value);
            super.setHeader(name, value);
        }

        @Override
        public void addHeader(String name, String value) {
            noteLength(name, value);
            super.addHeader(name, value);
        }

        @Override
        public void setIntHeader(String name, int value) {
            noteLength(name, String.valueOf(value));
            super.setIntHeader(name, value);
        }

        @Override
        public void addIntHeader(String name, int value) {
            noteLength(name, String.valueOf(value));
            super.addIntHeader(name, value);
        }

        private void noteLength(String name, String value) {
            if (HttpHeaders.CONTENT_LENGTH.equalsIgnoreCase(name) && value != null) {
                try {
                    declared = Long.parseLong(value.trim());
                } catch (NumberFormatException ignored) {
                    // not a length; the servlet container rejects it anyway
                }
            }
        }

        private final class CountingStream extends ServletOutputStream {
            private final ServletOutputStream delegate;

            CountingStream(ServletOutputStream delegate) {
                this.delegate = delegate;
            }

            @Override
            public void write(int b) throws IOException {
                try {
                    delegate.write(b);
                } catch (IOException e) {
                    aborted = true;
                    throw e;
                }
                written++;
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                try {
                    delegate.write(b, off, len);
                } catch (IOException e) {
                    aborted = true;
                    throw e;
                }
                written += len;
            }

            @Override
            public void flush() throws IOException {
                try {
                    delegate.flush();
                } catch (IOException e) {
                    aborted = true;
                    throw e;
                }
            }

            @Override
            public void close() throws IOException {
                try {
                    delegate.close();
                } catch (IOException e) {
                    aborted = true;
                    throw e;
                }
            }

            @Override
            public boolean isReady() {
                return delegate.isReady();
            }

            @Override
            public void setWriteListener(WriteListener listener) {
                delegate.setWriteListener(listener);
            }
        }
    }
}
