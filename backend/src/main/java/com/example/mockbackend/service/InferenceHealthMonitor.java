package com.example.mockbackend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Gauge;
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
 * <p>
 * A third field came the same day from failure drill FD18 (a wrong checkpoint): {@code loadError}, why the service's
 * model failed to load (null while it loads or once it is loaded). Before it, a failed load read as LOADING like a
 * model still loading, and jobs waited out inference.offline-wait-ms with nobody told. It stays LOADING and jobs wait
 * as before; {@link Alert#MODEL_LOAD_FAILED} is published once when the field turns to a text and once when it turns
 * back to null (the service was restarted). Missing, or neither text nor null, is unknown.
 * The admin overview (GET /api/v1/admin/overview, inference) shows the latest reading; API v1 healthz does not
 * (REVIEW_CHECKLIST #11: HealthResponse.inference was not taken into v1.2).
 * <p>
 * A fourth, {@code instanceId} (failure drill FD16b), is random per service process. RealJobWorker gives up on a
 * request still in flight to an earlier instance once a reading shows another one: a stopped and restarted Pod never
 * answers it, and it used to hold the only inference slot until inference.timeout-ms. A new instance between two
 * readings of the same state is only logged; the state, {@link Status#ready()} and the alerts do not change.
 * <p>
 * A fifth, {@code modelVersion} (API v1.2, from review/api-v1.1), names the loaded model. RealJobWorker records it on
 * runs the model failed (job_runs.modelVersion, so a failure counts against that model). A trailing {@code +rig} is
 * dropped: +rig means that job's rigging succeeded (REVIEW_CHECKLIST #18), which no failed run did.
 * <p>
 * Metrics (docs/METRICS.md §5.1): every check is a {@code health} request in beside.inference.request, and the latest
 * reading is the gauges {@code beside.inference.state} (state = UP | LOADING | DOWN | UNKNOWN, 1 for the current one,
 * 0 for the others) and {@code beside.inference.busy.seconds} (busyForMs / 1000, NaN when not reported), so Grafana
 * shows a GPU that went away or a run that is getting long before any job fails.
 */
@Component
@Profile("real")
public class InferenceHealthMonitor implements InferenceStatusSource {
    static final Duration CHECK_TIMEOUT = Duration.ofSeconds(2);
    /** inference.stuck-alert-ms default: about 14 normal A40 runs (8.7 s each, load test round 4). */
    static final long DEFAULT_STUCK_ALERT_MS = 120_000;
    /** Characters of the service's loadError kept (the service sends at most 200). */
    static final int MAX_LOAD_ERROR = 200;
    /** Characters of instanceId kept (the service sends 12). */
    static final int MAX_INSTANCE_ID = 64;
    /** Characters of modelVersion kept (job_runs.model_version, the service sends at most 128). */
    static final int MAX_MODEL_VERSION = 128;
    /** What /infer appends when that job's rigging succeeded (REVIEW_CHECKLIST #18). */
    static final String RIG_SUFFIX = "+rig";

    public enum State { UNKNOWN, UP, LOADING, DOWN }

    /**
     * One reading. {@code since} is when the current state began; {@code detail} never names hosts or paths.
     * {@code tokenOk} and {@code busyForMs} are what /healthz said, null when it said nothing usable (no answer, an
     * older service, or no token sent); they are not part of {@link #ready()}. So is {@code loadFailed}: true when
     * /healthz named a model load error, false when it said loadError null, null when it said nothing usable; then
     * {@code loadError} is the service's text on one line (null unless loadFailed). {@code instanceId} is the
     * service process that answered (null when nothing answered or an older service sent none). {@code modelVersion}
     * is the loaded model as /healthz names it, without +rig (null when none, not reported or nothing answered).
     */
    public record Status(State state, String device, Instant checkedAt, Instant since, String detail,
                         Boolean tokenOk, Long busyForMs, Boolean loadFailed, String loadError, String instanceId,
                         String modelVersion) {
        public Status(State state, String device, Instant checkedAt, Instant since, String detail) {
            this(state, device, checkedAt, since, detail, null, null);
        }

        public Status(State state, String device, Instant checkedAt, Instant since, String detail,
                      Boolean tokenOk, Long busyForMs) {
            this(state, device, checkedAt, since, detail, tokenOk, busyForMs, null, null);
        }

        public Status(State state, String device, Instant checkedAt, Instant since, String detail,
                      Boolean tokenOk, Long busyForMs, Boolean loadFailed, String loadError) {
            this(state, device, checkedAt, since, detail, tokenOk, busyForMs, loadFailed, loadError, null);
        }

        public Status(State state, String device, Instant checkedAt, Instant since, String detail,
                      Boolean tokenOk, Long busyForMs, Boolean loadFailed, String loadError, String instanceId) {
            this(state, device, checkedAt, since, detail, tokenOk, busyForMs, loadFailed, loadError, instanceId, null);
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
        STUCK,
        /** loadError set: the model failed to load, so the service stays LOADING until it is fixed and restarted. */
        MODEL_LOAD_FAILED
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
    private final InferenceMetrics metrics;
    private final Logger log = LoggerFactory.getLogger(InferenceHealthMonitor.class);
    private volatile Status status = new Status(State.UNKNOWN, null, null, Instant.now(), "not checked yet");
    /** Whether each alert was last published as active. Guarded by this. */
    private boolean tokenRejected;
    private boolean stuck;
    private boolean loadFailed;

    public InferenceHealthMonitor(String baseUrl, String token, ObjectMapper objectMapper,
                                  ApplicationEventPublisher events) {
        this(baseUrl, token, DEFAULT_STUCK_ALERT_MS, objectMapper, events);
    }

    /** {@code stuckAlertMs} 0 or below turns the STUCK alert off. Metered into a registry of its own. */
    public InferenceHealthMonitor(String baseUrl, String token, long stuckAlertMs, ObjectMapper objectMapper,
                                  ApplicationEventPublisher events) {
        this(baseUrl, token, stuckAlertMs, objectMapper, events, InferenceMetrics.detached());
    }

    @Autowired
    public InferenceHealthMonitor(@Value("${inference.base-url:http://localhost:8001}") String baseUrl,
                                  @Value("${inference.token:}") String token,
                                  @Value("${inference.stuck-alert-ms:" + DEFAULT_STUCK_ALERT_MS + "}") long stuckAlertMs,
                                  ObjectMapper objectMapper, ApplicationEventPublisher events, InferenceMetrics metrics) {
        this.healthUri = URI.create(baseUrl.replaceAll("/+$", "") + "/healthz");
        this.token = token == null ? "" : token.trim();
        this.stuckAlertMs = Math.max(0, stuckAlertMs);
        this.objectMapper = objectMapper;
        this.events = events;
        this.metrics = metrics;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CHECK_TIMEOUT)
                .build();
        for (State state : State.values()) {
            Gauge.builder("beside.inference.state", this, monitor -> monitor.status.state() == state ? 1 : 0)
                    .description("Latest /healthz reading of the inference service: 1 for the current state, 0 otherwise")
                    .tag("state", state.name())
                    .register(metrics.registry());
        }
        Gauge.builder("beside.inference.busy.seconds", this, monitor -> {
                    Long busy = monitor.status.busyForMs();
                    return busy == null ? Double.NaN : busy / 1000.0;
                })
                .description("Age of the oldest model run on the GPU (healthz busyForMs); NaN when not reported")
                .register(metrics.registry());
    }

    /** The latest reading (UNKNOWN until the first check). */
    public Status current() {
        return status;
    }

    /**
     * GET /api/v1/admin/overview: the latest reading as it is (state, device, checkedAt, since, detail and the fields
     * /healthz added: tokenOk, busyForMs, loadFailed, loadError, instanceId, modelVersion). Behind Basic auth in
     * deployment; loadError is the service's text with paths and the token already left out.
     */
    @Override
    public Object inferenceStatus() {
        return status;
    }

    /** Checks now; the scheduler calls it every inference.health-poll-ms, starting at startup. */
    @Scheduled(fixedDelayString = "${inference.health-poll-ms:15000}")
    public synchronized Status refresh() {
        Status previous = status;
        Status reading = check();
        if (reading.state() == previous.state()) {
            status = new Status(reading.state(), reading.device(), reading.checkedAt(), previous.since(), reading.detail(),
                    reading.tokenOk(), reading.busyForMs(), reading.loadFailed(), reading.loadError(), reading.instanceId(),
                    reading.modelVersion());
            if (reading.instanceId() != null && previous.instanceId() != null
                    && !reading.instanceId().equals(previous.instanceId())) {
                // Restarted between two readings (R7's supervisor brings a killed service back within seconds).
                log.info("inference service restarted between two checks (instance {} -> {}), still {}",
                        previous.instanceId(), reading.instanceId(), reading.state());
            }
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
        if (reading.loadFailed() != null && reading.loadFailed() != loadFailed) {
            loadFailed = reading.loadFailed();
            if (loadFailed) {
                log.warn("inference service could not load its model (healthz loadError: {}): it stays LOADING, so jobs "
                        + "wait PENDING up to inference.offline-wait-ms until it is fixed and restarted",
                        reading.loadError());
            } else {
                log.info("inference service no longer reports a model load error (healthz loadError=null)");
            }
            events.publishEvent(new AlertChanged(Alert.MODEL_LOAD_FAILED, loadFailed, reading, stuckAlertMs));
        }
    }

    /** One GET /healthz, timed as call=health (the HTTP exchange: a 200 with a body that is not JSON is still ok). */
    private Status check() {
        Instant now = Instant.now();
        HttpRequest.Builder request = HttpRequest.newBuilder(healthUri).timeout(CHECK_TIMEOUT).GET();
        if (!token.isEmpty()) {
            request.header("X-Inference-Token", token);
        }
        long started = System.nanoTime();
        HttpResponse<String> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Status(State.DOWN, null, now, now, "check interrupted");
        } catch (IOException | RuntimeException e) {
            metrics.record(InferenceMetrics.Call.HEALTH, InferenceMetrics.outcomeOf(e), started);
            return new Status(State.DOWN, null, now, now, e.getClass().getSimpleName());
        }
        metrics.record(InferenceMetrics.Call.HEALTH,
                response.statusCode() == 200 ? InferenceMetrics.Outcome.OK : InferenceMetrics.Outcome.ERROR, started);
        try {
            if (response.statusCode() != 200) {
                return new Status(State.DOWN, null, now, now, "healthz HTTP " + response.statusCode());
            }
            JsonNode body = objectMapper.readTree(response.body());
            String device = body.path("device").isTextual() ? body.path("device").asText() : null;
            boolean ready = body.path("modelLoaded").asBoolean(false) || body.path("passthrough").asBoolean(false);
            // tokenOk is about the header this check sent: without one, whatever the service says is not about us.
            JsonNode tokenOk = body.path("tokenOk");
            JsonNode busy = body.path("busyForMs");
            // loadError: a text = failed, null = none (loading or loaded), missing or anything else = unknown.
            JsonNode loadError = body.path("loadError");
            String error = loadError.isTextual() ? oneLine(loadError.asText()) : null;
            Boolean loadFailed = loadError.isNull() ? Boolean.FALSE : error != null ? Boolean.TRUE : null;
            JsonNode instance = body.path("instanceId");
            String instanceId = instance.isTextual() ? oneLine(instance.asText(), MAX_INSTANCE_ID) : null;
            JsonNode model = body.path("modelVersion");
            String modelVersion = model.isTextual() ? withoutRig(oneLine(model.asText(), MAX_MODEL_VERSION)) : null;
            return new Status(ready ? State.UP : State.LOADING, device, now, now, ready ? "ok" : "no model loaded",
                    !token.isEmpty() && tokenOk.isBoolean() ? Boolean.valueOf(tokenOk.booleanValue()) : null,
                    busy.isIntegralNumber() && busy.canConvertToLong() && busy.longValue() >= 0
                            ? Long.valueOf(busy.longValue()) : null,
                    loadFailed, error, instanceId, modelVersion);
        } catch (IOException | RuntimeException e) {
            return new Status(State.DOWN, null, now, now, e.getClass().getSimpleName());
        }
    }

    /** The service's text as one line (it reaches the log): control characters and runs of spaces become one space. */
    private static String oneLine(String text) {
        return oneLine(text, MAX_LOAD_ERROR);
    }

    /** As above, cut to {@code max} code points; blank is null. */
    private static String oneLine(String text, int max) {
        String line = text.replaceAll("[\\p{Cntrl}\\s]+", " ").strip();
        if (line.isEmpty()) {
            return null;
        }
        return line.codePointCount(0, line.length()) <= max ? line
                : line.substring(0, line.offsetByCodePoints(0, max)).strip();
    }

    /**
     * The model without a trailing +rig: an inference service from the review branch appended it whenever a rig
     * template was set, but +rig belongs only to runs whose rigging succeeded (REVIEW_CHECKLIST #18). Null stays null;
     * a value that was only "+rig" is null.
     */
    static String withoutRig(String modelVersion) {
        if (modelVersion == null || !modelVersion.endsWith(RIG_SUFFIX)) {
            return modelVersion;
        }
        String model = modelVersion.substring(0, modelVersion.length() - RIG_SUFFIX.length()).strip();
        return model.isEmpty() ? null : model;
    }
}
