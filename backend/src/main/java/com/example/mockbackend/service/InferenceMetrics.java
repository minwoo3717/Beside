package com.example.mockbackend.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Timer {@code beside.inference.request} (call, outcome; docs/METRICS.md §5.1): every HTTP request the real worker
 * ({@link InferenceClient}) and {@link InferenceHealthMonitor} send to the Python inference service, from sending to
 * the whole answer or to giving up. The JDK HttpClient has no automatic instrumentation, so both record here. The
 * outcome is about the HTTP exchange, not the job: a 200 whose body is unusable is still {@code ok}.
 * <p>
 * All 16 series are registered at startup in every profile, so dashboards show 0 instead of "no data" (mock never
 * calls the service and keeps them at 0). A request cut short by server shutdown (interrupted) is not recorded.
 */
@Component
public class InferenceMetrics {
    /** Health checks take tens of ms, an A40 run about 8.7 s, inference.timeout-ms is 120 s in real. */
    static final Duration[] REQUEST_SLO = {Duration.ofMillis(50), Duration.ofMillis(100), Duration.ofMillis(250),
            Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofMillis(2500), Duration.ofSeconds(5),
            Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(60), Duration.ofSeconds(120),
            Duration.ofSeconds(300), Duration.ofSeconds(600)};

    /** Which request: POST /infer or /infer/upload, the GLB GET, DELETE /artifacts/{jobId}, GET /healthz. */
    public enum Call {
        INFER, DOWNLOAD, CLEANUP, HEALTH;

        public String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * How it ended: {@code ok} the expected status (200, 2xx for cleanup); {@code error} another status or this
     * server's own failure; {@code timeout} no complete answer in time; {@code unavailable} no answer at all
     * (connection refused, dropped or not made in time, or the service restarted while the request waited).
     */
    public enum Outcome {
        OK, ERROR, TIMEOUT, UNAVAILABLE;

        public String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final MeterRegistry registry;
    private final Map<Call, Map<Outcome, Timer>> timers = new EnumMap<>(Call.class);

    public InferenceMetrics(MeterRegistry registry) {
        this.registry = registry;
        for (Call call : Call.values()) {
            Map<Outcome, Timer> byOutcome = new EnumMap<>(Outcome.class);
            for (Outcome outcome : Outcome.values()) {
                byOutcome.put(outcome, Timer.builder("beside.inference.request")
                        .description("HTTP requests to the Python inference service, sent to answered or given up")
                        .tags("call", call.tag(), "outcome", outcome.tag())
                        .serviceLevelObjectives(REQUEST_SLO)
                        .register(registry));
            }
            timers.put(call, byOutcome);
        }
    }

    /** For a client built outside Spring (tests, tools): its own registry, read through {@link #registry()}. */
    public static InferenceMetrics detached() {
        return new InferenceMetrics(new SimpleMeterRegistry());
    }

    /** Where the timers live; InferenceHealthMonitor adds its gauges here. */
    public MeterRegistry registry() {
        return registry;
    }

    /** One request that started at {@code startNanos} (System.nanoTime) and ended now. Never throws. */
    public void record(Call call, Outcome outcome, long startNanos) {
        try {
            timers.get(call).get(outcome).record(Math.max(0, System.nanoTime() - startNanos), TimeUnit.NANOSECONDS);
        } catch (RuntimeException ignored) {
            // metrics never change a request
        }
    }

    /**
     * A request that got no HTTP answer: no connection within the connect timeout and any other I/O error (refused,
     * reset, closed without an answer) are UNAVAILABLE, the request timeout TIMEOUT, anything else (a bad request
     * built here) ERROR.
     */
    public static Outcome outcomeOf(Throwable error) {
        if (error instanceof HttpConnectTimeoutException) {
            return Outcome.UNAVAILABLE;
        }
        if (error instanceof HttpTimeoutException) {
            return Outcome.TIMEOUT;
        }
        if (error instanceof IOException) {
            return Outcome.UNAVAILABLE;
        }
        return Outcome.ERROR;
    }
}
