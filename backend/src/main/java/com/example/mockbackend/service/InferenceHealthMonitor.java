package com.example.mockbackend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

/**
 * Profile "real": what the Python inference service answers on GET /healthz, checked every inference.health-poll-ms
 * (default 15 s). UP = reachable with the model loaded (or in passthrough), LOADING = reachable without a model
 * (still loading, failed to load or none configured), DOWN = an error status, an unreadable answer or no answer
 * within 2 s. The GPU is a teammate's PC that may be off: RealJobWorker reads this to keep jobs PENDING instead of
 * failing them at once, and every change of state is published as {@link StateChanged} (WebhookNotifier).
 * API v1 does not expose it yet; a healthz field is a contract addition for the meeting (REVIEW_CHECKLIST #11).
 */
@Component
@Profile("real")
public class InferenceHealthMonitor {
    static final Duration CHECK_TIMEOUT = Duration.ofSeconds(2);

    public enum State { UNKNOWN, UP, LOADING, DOWN }

    /** One reading. {@code since} is when the current state began; {@code detail} never names hosts or paths. */
    public record Status(State state, String device, Instant checkedAt, Instant since, String detail) {
        public boolean ready() {
            return state == State.UP;
        }
    }

    /** Published when a reading differs in state from the one before. */
    public record StateChanged(Status previous, Status current) {
    }

    private final URI healthUri;
    private final String token;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher events;
    private final HttpClient http;
    private final Logger log = LoggerFactory.getLogger(InferenceHealthMonitor.class);
    private volatile Status status = new Status(State.UNKNOWN, null, null, Instant.now(), "not checked yet");

    public InferenceHealthMonitor(@Value("${inference.base-url:http://localhost:8001}") String baseUrl,
                                  @Value("${inference.token:}") String token,
                                  ObjectMapper objectMapper, ApplicationEventPublisher events) {
        this.healthUri = URI.create(baseUrl.replaceAll("/+$", "") + "/healthz");
        this.token = token == null ? "" : token.trim();
        this.objectMapper = objectMapper;
        this.events = events;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CHECK_TIMEOUT)
                .build();
    }

    /** The latest reading (UNKNOWN until the first check). */
    public Status current() {
        return status;
    }

    /** Checks now; the scheduler calls it every inference.health-poll-ms, starting at startup. */
    @Scheduled(fixedDelayString = "${inference.health-poll-ms:15000}")
    public synchronized Status refresh() {
        Status previous = status;
        Status reading = check();
        if (reading.state() == previous.state()) {
            status = new Status(reading.state(), reading.device(), reading.checkedAt(), previous.since(), reading.detail());
            return status;
        }
        status = reading;
        log.info("inference service {} -> {} ({})", previous.state(), reading.state(), reading.detail());
        events.publishEvent(new StateChanged(previous, reading));
        return status;
    }

    private Status check() {
        Instant now = Instant.now();
        HttpRequest.Builder request = HttpRequest.newBuilder(healthUri).timeout(CHECK_TIMEOUT).GET();
        if (!token.isEmpty()) {
            request.header("X-Inference-Token", token);
        }
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return new Status(State.DOWN, null, now, now, "healthz HTTP " + response.statusCode());
            }
            JsonNode body = objectMapper.readTree(response.body());
            String device = body.path("device").isTextual() ? body.path("device").asText() : null;
            boolean ready = body.path("modelLoaded").asBoolean(false) || body.path("passthrough").asBoolean(false);
            return new Status(ready ? State.UP : State.LOADING, device, now, now, ready ? "ok" : "no model loaded");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Status(State.DOWN, null, now, now, "check interrupted");
        } catch (IOException | RuntimeException e) {
            return new Status(State.DOWN, null, now, now, e.getClass().getSimpleName());
        }
    }
}
