package com.example.mockbackend;

import com.example.mockbackend.config.TracingConfig;
import com.example.mockbackend.service.JobTracing;
import io.micrometer.tracing.exporter.FinishedSpan;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.MDC;
import org.springframework.boot.actuate.autoconfigure.tracing.otlp.OtlpAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.support.PropertiesLoaderUtils;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the tracing setup relies on (W8, 2026-11-26), without a server: Spring Boot 3.2 creates the OTLP span exporter
 * only when management.otlp.tracing.endpoint is set (compose sets http://tempo:4318/v1/traces; application.properties
 * does not, so tests and local runs export nothing), every trace is sampled, the logs show the trace, routine spans
 * stay out of Tempo, and without a Tracer (tracing off) JobTracing does nothing and sends no traceparent.
 */
class TracingExportConfigTest {
    private final ApplicationContextRunner otlp = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OtlpAutoConfiguration.class));

    @Test
    void theOtlpExporterExistsOnlyWithAnEndpoint() {
        otlp.run(context -> assertThat(context).doesNotHaveBean(OtlpHttpSpanExporter.class));
        otlp.withPropertyValues("management.otlp.tracing.endpoint=http://tempo:4318/v1/traces")
                .run(context -> assertThat(context).hasSingleBean(OtlpHttpSpanExporter.class));
    }

    @Test
    void theMainSettingsTraceEverythingExportNothingAndLogTheTrace() throws Exception {
        Properties main = PropertiesLoaderUtils.loadAllProperties("application.properties");
        assertThat(main.getProperty("management.otlp.tracing.endpoint")).as("set by compose only").isNull();
        assertThat(main.getProperty("management.tracing.sampling.probability")).isEqualTo("1.0");
        assertThat(main.getProperty("logging.pattern.level")).contains("trace=%X{traceId:-}");
        assertThat(main.getProperty("logging.pattern.correlation")).as("no second [traceId-spanId] block").isEmpty();
        assertThat(main.getProperty("spring.application.name")).isEqualTo("beside-backend");
    }

    @Test
    void routineSpansAreNotExported() {
        assertThat(TracingConfig.exportable(span("http get /actuator/prometheus", Map.of("uri", "/actuator/prometheus"))))
                .isFalse();
        assertThat(TracingConfig.exportable(span("http get /api/v1/healthz", Map.of("uri", "/api/v1/healthz"))))
                .isFalse();
        assertThat(TracingConfig.exportable(span("task refresh",
                Map.of("code.namespace", "com.example.mockbackend.service.JobQueueGauge", "code.function", "refresh"))))
                .isFalse();
        assertThat(TracingConfig.exportable(span("http post /api/v1/jobs", Map.of("uri", "/api/v1/jobs")))).isTrue();
        assertThat(TracingConfig.exportable(span(JobTracing.RUN_SPAN, Map.of("jobId", "j", "worker", "real"))))
                .isTrue();
        assertThat(TracingConfig.exportable(span("POST /infer/upload", Map.of("beside.call", "infer")))).isTrue();
    }

    @Test
    void withoutATracerNothingHappensAndNoTraceparentIsSent() {
        JobTracing off = JobTracing.NOOP;
        off.dispatching("job-off", 1);
        try (JobTracing.Run run = off.startRun("job-off", 1, "real");
             JobTracing.Scoped call = off.call("POST /infer", "infer", "http://gpu/infer")) {
            assertThat(run.traceId()).isEqualTo("-");
            assertThat(MDC.get(JobTracing.MDC_TRACE_ID)).isNull();
            HttpRequest request = off.inject(HttpRequest.newBuilder(URI.create("http://gpu/infer"))).GET().build();
            assertThat(request.headers().map()).doesNotContainKey("traceparent");
            run.failed("INFERENCE_FAILED", null);
            call.tag("http.status_code", 500);
        }
        assertThat(MDC.get(JobTracing.MDC_SPAN_ID)).isNull();
    }

    private static FinishedSpan span(String name, Map<String, String> tags) {
        FinishedSpan span = Mockito.mock(FinishedSpan.class);
        Mockito.when(span.getName()).thenReturn(name);
        Mockito.when(span.getTags()).thenReturn(tags);
        return span;
    }
}
