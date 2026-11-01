package com.example.mockbackend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Operational metrics (docs/METRICS.md §5) through the real Tomcat: Actuator answers only on the management port
 * (8081 in a real run, random here), the public port has no /actuator at all, and the Prometheus scrape carries the
 * API response time histogram with the SLO buckets and the env tag. MockMvc cannot show this: the management server is
 * a separate child context on its own port.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability(tracing = false) // tests switch metric export off unless asked; the endpoint needs it
class ObservabilityEndpointTest {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @LocalServerPort
    int port;

    @LocalManagementPort
    int managementPort;

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

    private HttpResponse<String> get(int onPort, String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + onPort + path))
                .timeout(Duration.ofSeconds(10)).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
