package com.example.mockbackend.service;

import com.example.mockbackend.config.RequestIdFilter;
import com.example.mockbackend.config.TracingConfig;
import io.micrometer.tracing.Link;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.http.HttpRequest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Distributed tracing of a job run (W8, 2026-11-26; one trace with its request since 2026-11-27, obs-wave3 A2;
 * deploy/README.md '로그 · 알림'): where the time of one job goes, between the app, this server and the GPU service, as
 * one trace in Grafana Tempo.
 * <ul>
 *   <li>A job runs on an {@code @Async} worker thread after the upload's 202 has gone out. {@link #dispatching}
 *       remembers the dispatching request's span (upload or retry), its X-Request-Id and the time in memory until the
 *       worker starts (nothing in the database). The run's root span {@value #RUN_SPAN} (tags jobId, attempt, worker,
 *       {@value #REQUEST_ID_ATTRIBUTE}; at the end job.result and job.error_code) is then a child of that request's
 *       server span, next to a span {@value #DISPATCH_WAIT_SPAN} for the time in the @Async queue. When the app sent a
 *       W3C traceparent whose trace id is its flow's X-Request-Id, that is the trace of the whole flow.</li>
 *   <li>The old shape stays for a run whose request was not sampled (the app's traceparent flags 00) or is not known
 *       (the server restarted in between, a test that starts a run directly): a trace of its own, started with no parent,
 *       linked to the request's trace and naming it in {@value #DISPATCHED_FROM}. The builder never gets both
 *       setNoParent and setParent: with micrometer-tracing-bridge-otel 1.2 no-parent wins.</li>
 *   <li>Stages are child spans ({@link #stage}): cutout, waiting for the inference slot, the /healthz wait, storing the
 *       GLB; the inference HTTP calls are client spans ({@link #call}) whose W3C {@code traceparent} InferenceClient
 *       sends to the Python service with X-Request-Id ({@link #inject}).</li>
 *   <li>While a span is open its trace and span ids are in the MDC ({@code traceId}, {@code spanId}): the text log's
 *       {@code trace=} and the prod JSON fields (logback-spring.xml), so a Loki line leads to its trace. The OpenTelemetry
 *       bridge's own MDC listener does the same, but only once its JVM-wide context wrapper is in place; this keeps the
 *       job lines right either way. The previous values come back when the span closes: pool threads are reused.</li>
 * </ul>
 * Spans only, no metrics: the times are already timers (beside.job.*, beside.inference.request, docs/METRICS.md §5),
 * so a Micrometer Observation here would add the same numbers again under new names. Without a Tracer bean (tracing
 * off, as in tests unless they ask for it) everything here is a no-op ({@link #NOOP}).
 */
@Component
public class JobTracing {
    /** Root span of one job run (one attempt). */
    public static final String RUN_SPAN = "beside.job.run";
    /** Sibling of the run under the request's span: from the hand-over to the worker thread picking the run up. */
    public static final String DISPATCH_WAIT_SPAN = "beside.job.dispatch-wait";
    /** Attribute of a run in a trace of its own: trace id of the request that dispatched it (upload or retry). */
    public static final String DISPATCHED_FROM = "beside.dispatched.trace_id";
    /** Attribute of the run: X-Request-Id of the request that dispatched it (same key as on the server spans). */
    public static final String REQUEST_ID_ATTRIBUTE = TracingConfig.REQUEST_ID_TAG;
    /** MDC keys (Micrometer Tracing's names; logback-spring.xml, application.properties logging.pattern.level). */
    public static final String MDC_TRACE_ID = "traceId";
    public static final String MDC_SPAN_ID = "spanId";
    /** Dispatched runs remembered at most: a run that never starts (a mocked worker in a test) must not pile up. */
    static final int MAX_REMEMBERED = 1_000;
    public static final JobTracing NOOP = new JobTracing(Tracer.NOOP, Propagator.NOOP);

    /** What {@link #dispatching} remembers of the request until the run starts. */
    record Origin(TraceContext context, String requestId, long dispatchedAtMs) {
    }

    private final Tracer tracer;
    private final Propagator propagator;
    private final Map<String, Origin> dispatchedFrom = new ConcurrentHashMap<>();

    @Autowired
    public JobTracing(ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
        this(tracer.getIfAvailable(() -> Tracer.NOOP), propagator.getIfAvailable(() -> Propagator.NOOP));
    }

    public JobTracing(Tracer tracer, Propagator propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    /**
     * Called by the request thread right before it hands the job to the worker: the request's span (if any) becomes
     * the run's parent, or its link when it is not sampled. {@link #forget} when the hand-over failed.
     */
    public void dispatching(String jobId, int attempt) {
        TraceContext current = tracer.currentTraceContext().context();
        if (current != null && !current.traceId().isEmpty() && dispatchedFrom.size() < MAX_REMEMBERED) {
            dispatchedFrom.put(key(jobId, attempt),
                    new Origin(current, MDC.get(RequestIdFilter.MDC_KEY), System.currentTimeMillis()));
        }
    }

    public void forget(String jobId, int attempt) {
        dispatchedFrom.remove(key(jobId, attempt));
    }

    /** The root span of a run, in scope on this thread until {@link Run#close()}. */
    public Run startRun(String jobId, int attempt, String worker) {
        Origin origin = dispatchedFrom.remove(key(jobId, attempt));
        boolean inRequestTrace = origin != null && Boolean.TRUE.equals(origin.context().sampled());
        Span.Builder builder = tracer.spanBuilder();
        if (inRequestTrace) {
            Span wait = tracer.spanBuilder().setParent(origin.context()).name(DISPATCH_WAIT_SPAN)
                    .tag("jobId", jobId).tag("attempt", attempt)
                    .startTimestamp(origin.dispatchedAtMs(), TimeUnit.MILLISECONDS)
                    .start();
            wait.end();
            builder = builder.setParent(origin.context());
        } else {
            builder = builder.setNoParent();
            if (origin != null) {
                builder = builder.addLink(new Link(origin.context())).tag(DISPATCHED_FROM, origin.context().traceId());
            }
        }
        builder = builder.name(RUN_SPAN).tag("jobId", jobId).tag("attempt", attempt).tag("worker", worker);
        if (origin != null && origin.requestId() != null) {
            builder = builder.tag(REQUEST_ID_ATTRIBUTE, origin.requestId());
        }
        return new Run(builder.start());
    }

    /** A child span of the span in scope (a stage of the run), in scope itself until {@link Scoped#close()}. */
    public Scoped stage(String name) {
        return new Scoped(tracer.nextSpan().name(name).start());
    }

    /**
     * A client span for one HTTP request to the inference service, child of the span in scope: name is the method and
     * the path template ("POST /infer/upload"), {@code call} the beside.inference.request call tag. In scope until
     * closed, so {@link #inject} sends its context.
     */
    public Scoped call(String name, String call, String url) {
        String method = name.substring(0, Math.max(0, name.indexOf(' ')));
        Span.Builder builder = tracer.spanBuilder();
        TraceContext parent = tracer.currentTraceContext().context();
        if (parent != null) {
            builder = builder.setParent(parent);
        }
        Span span = builder.name(name).kind(Span.Kind.CLIENT)
                .tag("http.method", method).tag("http.url", url).tag("beside.call", call)
                .remoteServiceName("beside-inference")
                .start();
        return new Scoped(span);
    }

    /** W3C traceparent (and whatever else the propagator writes) of the span in scope; nothing without one. */
    public HttpRequest.Builder inject(HttpRequest.Builder builder) {
        TraceContext current = tracer.currentTraceContext().context();
        if (current != null && !current.traceId().isEmpty()) {
            propagator.inject(current, builder, HttpRequest.Builder::header);
        }
        return builder;
    }

    private static String key(String jobId, int attempt) {
        return jobId + ":" + attempt;
    }

    /** A span in scope on this thread, with its ids in the MDC; close() ends it and puts back what was there. */
    public class Scoped implements AutoCloseable {
        protected final Span span;
        private final Tracer.SpanInScope scope;
        private final String previousTraceId;
        private final String previousSpanId;
        private boolean closed;

        Scoped(Span span) {
            this.span = span;
            // before withSpan: the bridge's MDC listener may already write the new ids there
            this.previousTraceId = MDC.get(MDC_TRACE_ID);
            this.previousSpanId = MDC.get(MDC_SPAN_ID);
            this.scope = tracer.withSpan(span);
            TraceContext context = span.context();
            if (context != null && !context.traceId().isEmpty()) {
                MDC.put(MDC_TRACE_ID, context.traceId());
                MDC.put(MDC_SPAN_ID, context.spanId());
            }
        }

        /** The trace id, "-" when tracing is off. */
        public String traceId() {
            TraceContext context = span.context();
            return context == null || context.traceId().isEmpty() ? "-" : context.traceId();
        }

        public Scoped tag(String key, String value) {
            if (value != null) {
                span.tag(key, value);
            }
            return this;
        }

        public Scoped tag(String key, long value) {
            span.tag(key, value);
            return this;
        }

        /** Marks the span failed (status ERROR) with what ended it. */
        public Scoped failed(Throwable cause) {
            span.error(cause);
            return this;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                scope.close();
            } finally {
                span.end();
                restore(MDC_TRACE_ID, previousTraceId);
                restore(MDC_SPAN_ID, previousSpanId);
            }
        }

        private void restore(String key, String value) {
            if (value == null) {
                MDC.remove(key);
            } else {
                MDC.put(key, value);
            }
        }
    }

    /** The root span of a run. */
    public final class Run extends Scoped {
        Run(Span span) {
            super(span);
        }

        /** job.result COMPLETED. */
        public void completed() {
            span.tag("job.result", "COMPLETED");
        }

        /** job.result FAILED with the job failure code; status ERROR with {@code cause} (or one naming the code). */
        public void failed(String code, Throwable cause) {
            span.tag("job.result", "FAILED");
            span.tag("job.error_code", code);
            span.error(cause != null ? cause : new RunFailed(code));
        }

        /** job.result NOT_STARTED: canceled or retried before it reached the inference service. */
        public void notStarted() {
            span.tag("job.result", "NOT_STARTED");
        }
    }

    /** What a failed run's span records when the failure has no exception of its own (no stack trace). */
    static final class RunFailed extends RuntimeException {
        RunFailed(String code) {
            super(code, null, false, false);
        }
    }
}
