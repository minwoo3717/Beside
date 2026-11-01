package com.example.mockbackend;

import com.example.mockbackend.domain.Job;
import com.example.mockbackend.domain.JobStatus;
import com.example.mockbackend.repository.JobRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Operational metrics (docs/METRICS.md §5) through the real Tomcat: Actuator answers only on the management port
 * (8081 in a real run, random here), the public port has no /actuator at all, and the Prometheus scrape carries the
 * API response time histogram with the SLO buckets and the env tag. A GLB download the client resets midway is a
 * client_abort. MockMvc cannot show this: the management server is a separate child context on its own port, and only
 * a real connection can break.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability(tracing = false) // tests switch metric export off unless asked; the endpoint needs it
@ExtendWith(OutputCaptureExtension.class)
class ObservabilityEndpointTest {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @LocalServerPort
    int port;

    @LocalManagementPort
    int managementPort;

    @TempDir
    static Path storage;

    @Autowired
    JobRepository jobRepository;

    @Autowired
    MeterRegistry meters;

    @Test
    void managementRunsOnItsOwnPort() {
        assertThat(managementPort).isPositive().isNotEqualTo(port);
    }

    @Test
    void publicPortServesNoActuator() throws Exception {
        for (String path : new String[]{"/actuator", "/actuator/prometheus", "/actuator/health", "/actuator/env"}) {
            assertThat(get(port, path).statusCode()).as("public %s", path).isEqualTo(404);
        }
    }

    @Test
    void managementPortServesHealthWithoutDetails() throws Exception {
        HttpResponse<String> health = get(managementPort, "/actuator/health");
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("\"status\":\"UP\"").doesNotContain("components").doesNotContain("details");
        // Only health and prometheus are exposed.
        assertThat(get(managementPort, "/actuator/env").statusCode()).isEqualTo(404);
        assertThat(get(managementPort, "/actuator/beans").statusCode()).isEqualTo(404);
    }

    @Test
    void prometheusScrapeHasTheResponseTimeHistogram() throws Exception {
        assertThat(get(port, "/api/v1/healthz").statusCode()).isEqualTo(200);
        // The request timer stops after the response went out: the scrape may come a moment before it.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            HttpResponse<String> scrape = get(managementPort, "/actuator/prometheus");
            assertThat(scrape.statusCode()).isEqualTo(200);
            assertThat(scrape.body())
                    .contains("http_server_requests_seconds_bucket{")
                    .contains("uri=\"/api/v1/healthz\"")
                    .contains("le=\"0.025\"").contains("le=\"2.5\"").contains("le=\"30.0\"")
                    .contains("env=\"local\"")
                    .contains("jvm_memory_used_bytes")
                    .contains("process_cpu_usage")
                    .contains("tomcat_threads_busy_threads")
                    .contains("hikaricp_connections")
                    .contains("disk_free_bytes");
        });
    }

    @Test
    void prometheusScrapeHasTheJobMeters() throws Exception {
        String scrape = get(managementPort, "/actuator/prometheus").body();
        // Registered at startup, so dashboards show 0 instead of "no data" before the first job.
        assertThat(scrape)
                .contains("beside_jobs_created_total{env=\"local\",}")
                .contains("beside_jobs_retried_total{")
                .contains("beside_jobs_queue{env=\"local\",state=\"pending\",}")
                .contains("beside_jobs_queue{env=\"local\",state=\"processing\",}");
    }

    @Test
    void aDownloadTheClientAbandonsIsCountedAsClientAbort(CapturedOutput output) throws Exception {
        // A GLB far larger than the socket buffers, so the server is still writing when the client resets the connection.
        Path big = storage.resolve("big.glb");
        try (RandomAccessFile file = new RandomAccessFile(big.toFile(), "rw")) {
            file.setLength(32L * 1024 * 1024);
        }
        Instant now = Instant.now();
        Job job = new Job();
        job.setId(UUID.randomUUID().toString());
        job.setStatus(JobStatus.COMPLETED);
        job.setCreatedAt(now);
        job.setUpdatedAt(now);
        job.setQueuedAt(now);
        job.setFinishedAt(now);
        job.setAttempt(1);
        job.setProgress(1.0);
        job.setResultPath(big.toString());
        jobRepository.save(job);
        double aborted = meters.get("beside.asset.downloads").tag("outcome", "client_abort").counter().count();

        try (Socket socket = new Socket()) {
            socket.setReceiveBufferSize(8 * 1024);
            socket.connect(new InetSocketAddress("127.0.0.1", port), 5_000);
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            out.write(("GET /api/v1/jobs/" + job.getId() + "/asset HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = socket.getInputStream();
            assertThat(new String(in.readNBytes(12), StandardCharsets.US_ASCII)).isEqualTo("HTTP/1.1 200");
            in.readNBytes(16 * 1024);
            socket.setSoLinger(true, 0); // close with a reset, as a phone dropping the connection does
        }

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(
                meters.get("beside.asset.downloads").tag("outcome", "client_abort").counter().count() - aborted)
                .isEqualTo(1.0));
        assertThat(meters.get("beside.asset.downloads").tag("outcome", "ok").counter().count()).isZero();
        // Nobody to answer and nothing broken on the server: no ERROR stack (GlobalExceptionHandler logs it at DEBUG).
        assertThat(output.getOut()).doesNotContain("Internal error").doesNotContain("ClientAbortException");
    }

    private HttpResponse<String> get(int onPort, String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + onPort + path))
                .timeout(Duration.ofSeconds(10)).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
