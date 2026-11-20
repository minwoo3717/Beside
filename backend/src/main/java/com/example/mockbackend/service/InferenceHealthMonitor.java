package com.example.mockbackend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
 * <p>
 * The check sends X-Inference-Token when inference.token is set (never logged) and also reads two fields the service
 * added on 2026-11-20: {@code tokenOk} (would /infer take that token) and {@code busyForMs} (age of the oldest model
 * run still on the GPU). They never change the state or {@link Status#ready()}: a job sent to a service that rejects
 * the token fails at once INFERENCE_UNAVAILABLE as before, instead of waiting out inference.offline-wait-ms. They only
 * raise {@link AlertChanged} events (WebhookNotifier): {@link Alert#TOKEN_REJECTED} while tokenOk is false (failure
 * drill FD17) and {@link Alert#STUCK} while busyForMs is above inference.stuck-alert-ms (FD13: healthz UP, inference
 * stuck), each once when it starts and once when it ends. A reading without the field (DOWN, an older service, no
 * token sent) is unknown and changes nothing.
 * API v1 does not expose it yet; a healthz field is a contract addition for the meeting (REVIEW_CHECKLIST #11).
 */
@Component
@Profile("real")
public class InferenceHealthMonitor {
    static final Duration CHECK_TIMEOUT = Duration.ofSeconds(2);
    /** inference.stuck-alert-ms default: about 14 normal A40 runs (8.7 s each, load test round 4). */
    static final long DEFAULT_STUCK_ALERT_MS = 120_000;

    public enum State { UNKNOWN, UP, LOADING, DOWN }

    /**
     * One reading. {@code since} is when the current state began; {@code detail} never names hosts or paths.
     * {@code tokenOk} and {@code busyForMs} are what /healthz said, null when it said nothing usable (no answer, an
     * older service, or no token sent); they are not part of {@link #ready()}.
     */
    public record Status(State state, String device, Instant checkedAt, Instant since, String detail,
                         Boolean tokenOk, Long busyForMs) {
        public Status(State state, String device, Instant checkedAt, Instant since, String detail) {
            this(state, device, checkedAt, since, detail, null, null);
        }

        public boolean ready() {
            return state == State.UP;
        }
    }

    /** Published when a reading differs in state from the one before. */
    public record StateChanged(Status previous, Status current) {
    }

    /** What /healthz can report beside the state. */
    public enum Alert {
        /** tokenOk=false: the service would answer 401 to every /infer (inference.token differs from its token). */
        TOKEN_REJECTED,
        /** busyForMs above inference.stuck-alert-ms: one run has held the GPU for too long. */
        STUCK
    }

    /**
     * Published when an alert starts ({@code active}) or ends, never while it stays. {@code current} is the reading
     * that changed it; {@code stuckAlertMs} is the limit in force (for the message).
     */
    public record AlertChanged(Alert alert, boolean active, Status current, long stuckAlertMs) {
    }

    private final URI healthUri;
    private final String token;
    private final long stuckAlertMs;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher events;
    private final HttpClient http;
    private final Logger log = LoggerFactory.getLogger(InferenceHealthMonitor.class);
    private volatile Status status = new Status(State.UNKNOWN, null, null, Instant.now(), "not checked yet");
    /** Whether each alert was last published as active. Guarded by this. */
    private boolean tokenRejected;
    private boolean stuck;

    public InferenceHealthMonitor(String baseUrl, String token, ObjectMapper objectMapper,
                                  ApplicationEventPublisher events) {
        this(baseUrl, token, DEFAULT_STUCK_ALERT_MS, objectMapper, events);
    }

    /** {@code stuckAlertMs} 0 or below turns the STUCK alert off. */
    @Autowired
    public InferenceHealthMonitor(@Value("${inference.base-url:http://localhost:8001}") String baseUrl,
                                  @Value("${inference.token:}") String token,
                                  @Value("${inference.stuck-alert-ms:" + DEFAULT_STUCK_ALERT_MS + "}") long stuckAlertMs,
                                  ObjectMapper objectMapper, ApplicationEventPublisher events) {
        this.healthUri = URI.create(baseUrl.replaceAll("/+$", "") + "/healthz");
        this.token = token == null ? "" : token.trim();
        this.stuckAlertMs = Math.max(0, stuckAlertMs);
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
            status = new Status(reading.state(), reading.device(), reading.checkedAt(), previous.since(), reading.detail(),
                    reading.tokenOk(), reading.busyForMs());
        } else {
            status = reading;
            log.info("inference service {} -> {} ({})", previous.state(), reading.state(), reading.detail());
            events.publishEvent(new StateChanged(previous, reading));
        }
        updateAlerts(status);
        return status;
    }

    /** Caller holds the lock. Unknown fields (null) leave both alerts as they are. */
    private void updateAlerts(Status reading) {
        if (reading.tokenOk() != null) {
            boolean rejected = !reading.tokenOk();
            if (rejected != tokenRejected) {
                tokenRejected = rejected;
                if (rejected) {
                    log.warn("inference service rejects inference.token (healthz tokenOk=false): every job would fail "
                            + "INFERENCE_UNAVAILABLE until the two tokens match");
                } else {
                    log.info("inference service accepts inference.token again (healthz tokenOk=true)");
                }
                events.publishEvent(new AlertChanged(Alert.TOKEN_REJECTED, rejected, reading, stuckAlertMs));
            }
        }
        if (stuckAlertMs > 0 && reading.busyForMs() != null) {
            boolean over = reading.busyForMs() > stuckAlertMs;
            if (over != stuck) {
                stuck = over;
                if (over) {
                    log.warn("inference service has been busy with one run for {} ms (healthz busyForMs, limit {} ms): "
                            + "inference may be stuck", reading.busyForMs(), stuckAlertMs);
                } else {
                    log.info("inference service is no longer stuck (healthz busyForMs={})", reading.busyForMs());
                }
                events.publishEvent(new AlertChanged(Alert.STUCK, over, reading, stuckAlertMs));
            }
        }
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
            // tokenOk is about the header this check sent: without one, whatever the service says is not about us.
            JsonNode tokenOk = body.path("tokenOk");
            JsonNode busy = body.path("busyForMs");
            return new Status(ready ? State.UP : State.LOADING, device, now, now, ready ? "ok" : "no model loaded",
                    !token.isEmpty() && tokenOk.isBoolean() ? Boolean.valueOf(tokenOk.booleanValue()) : null,
                    busy.isIntegralNumber() && busy.canConvertToLong() && busy.longValue() >= 0
                            ? Long.valueOf(busy.longValue()) : null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Status(State.DOWN, null, now, now, "check interrupted");
        } catch (IOException | RuntimeException e) {
            return new Status(State.DOWN, null, now, now, e.getClass().getSimpleName());
        }
    }
}
