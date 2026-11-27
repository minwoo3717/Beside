package com.example.mockbackend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The product event meters (docs/METRICS.md §6.5) are on the Prometheus scrape from startup, every name × platform and
 * every drop reason at 0, so a Grafana panel shows 0 instead of "no data" before the first event. A class of its own:
 * ObservabilityEndpointTest belongs to the operational meters of §5.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability(tracing = false)
class ProductEventMetersTest {
    /** docs/METRICS.md §6.1, written out on purpose (app_error, saved_dog_open, saved_dog_change: 2026-11-27). */
    private static final List<String> NAMES = List.of("screen_view", "photos_selected", "generate_tap", "job_cancel_tap",
            "job_failed_shown", "model_ready", "fur_toggle", "ar_issue_shown", "model_placed", "animation_play",
            "photo_saved", "start_over", "app_error", "saved_dog_open", "saved_dog_change");
    private static final List<String> REASONS = List.of("unknown_name", "invalid", "duplicate", "rate_limited", "bad_prop");

    @LocalManagementPort
    int managementPort;

    @Test
    void everyNameAndReasonIsOnTheScrapeAtZero() throws Exception {
        HttpResponse<String> scrape = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + managementPort + "/actuator/prometheus"))
                        .timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(scrape.statusCode()).isEqualTo(200);
        String body = scrape.body();
        for (String name : NAMES) {
            for (String platform : List.of("android", "web")) {
                assertThat(body).contains("beside_app_events_total{env=\"local\",name=\"" + name + "\",platform=\"" + platform + "\",} 0.0");
            }
        }
        for (String reason : REASONS) {
            assertThat(body).contains("beside_app_events_dropped_total{env=\"local\",reason=\"" + reason + "\",} 0.0");
        }
        // Exactly the documented names: nothing else under beside_app_events_total.
        long series = body.lines().filter(line -> line.startsWith("beside_app_events_total{")).count();
        assertThat(series).isEqualTo(NAMES.size() * 2L);
    }
}
