package com.example.mockbackend.config;

import com.example.mockbackend.exception.GlobalExceptionHandler;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.ObservationFilter;
import io.micrometer.tracing.exporter.FinishedSpan;
import io.micrometer.tracing.exporter.SpanExportingPredicate;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;

import java.util.Map;

/**
 * Distributed tracing around Spring Boot's own (W8, 2026-11-26; application.properties 'Distributed tracing'). Boot
 * traces every HTTP request and @Scheduled run; the job runs are traced by JobTracing.
 * <ul>
 *   <li>{@link #routineSpansStayHere()}: spans nobody opens in Tempo are not exported — Prometheus' scrape and the
 *       health checks (/actuator/**, /api/v1/healthz: about 9,000 a day) and the scheduled refreshes (queue gauge every
 *       5 s, GPU health every 15 s). They still exist in-process (log correlation), and their metrics are unchanged:
 *       this filters exported spans, not observations.</li>
 *   <li>{@link #requestIdOnServerSpans()}: the request's X-Request-Id (RequestIdFilter, the app's id) on its server
 *       span as {@value #REQUEST_ID_TAG}, so Tempo finds the upload of a phone's [BesideMetric] line. A high-cardinality
 *       key value: spans only, never a metric tag. The same filter rewrites Spring's {@value #URL_TAG} (the raw
 *       request URI) with {@link GlobalExceptionHandler#pathForLog}, so a share link's token keeps only its first 6
 *       characters in Tempo as in the logs (openapi.yaml: 서버 로그에는 앞 6자만; 2026-11-26 pre-deploy review).</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class TracingConfig {
    public static final String REQUEST_ID_TAG = "beside.request_id";
    /** Spring Framework 6.1's high-cardinality server key value: HttpServletRequest#getRequestURI as it came. */
    public static final String URL_TAG = "http.url";

    @Bean
    SpanExportingPredicate routineSpansStayHere() {
        return TracingConfig::exportable;
    }

    @Bean
    ObservationFilter requestIdOnServerSpans() {
        return context -> {
            if (context instanceof ServerRequestObservationContext server) {
                // stop() runs inside RequestIdFilter (it is the first filter), so the id is still in the MDC
                String requestId = MDC.get(RequestIdFilter.MDC_KEY);
                if (requestId != null) {
                    context.addHighCardinalityKeyValue(KeyValue.of(REQUEST_ID_TAG, requestId));
                }
                // Filters run after the convention's key values are added (Observation#stop), so this replaces them
                if (server.getCarrier() != null && server.getCarrier().getRequestURI() != null) {
                    context.addHighCardinalityKeyValue(
                            KeyValue.of(URL_TAG, GlobalExceptionHandler.pathForLog(server.getCarrier().getRequestURI())));
                }
            }
            return context;
        };
    }

    /** False for a server span of /actuator/** or /api/v1/healthz and for a scheduled task's span. */
    public static boolean exportable(FinishedSpan span) {
        Map<String, String> tags = span.getTags();
        String uri = tags.get("uri");
        if (uri != null && (uri.startsWith("/actuator") || uri.equals("/api/v1/healthz"))) {
            return false;
        }
        // Spring's @Scheduled observation (tasks.scheduled.execution): "task <method>" with code.namespace/function
        String name = span.getName();
        return !(tags.containsKey("code.namespace") && name != null && name.startsWith("task "));
    }
}
