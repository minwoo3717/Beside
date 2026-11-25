package com.example.mockbackend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.logging.LoggingInitializationContext;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.core.env.PropertiesPropertySource;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Console format of logback-spring.xml (docs/ops/RUNBOOK.md '먼저 볼 곳'): prod writes one JSON object per line with
 * requestId / jobId / attempt / traceId / spanId, a UTC timestamp and the stack trace in one field; every other profile
 * writes Spring Boot's text with [rid=... job=... trace=...] (application.properties logging.pattern.level).
 * <p>
 * Not a {@code @SpringBootTest}: Spring Boot initialises logging once per JVM, with the profiles of the first context
 * (LogbackLoggingSystem skips an initialised context), so a prod context in a full test run shows JSON only when it
 * happens to start first. This test initialises the logging system itself with the real file and application.properties,
 * then puts the text console back for the tests after it.
 */
@ExtendWith(OutputCaptureExtension.class)
class LoggingFormatTest {
    private static final LoggingSystem LOGGING = LoggingSystem.get(LoggingFormatTest.class.getClassLoader());
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private final Logger log = LoggerFactory.getLogger(LoggingFormatTest.class);

    @AfterEach
    void backToTheTextConsole() throws IOException {
        MDC.clear();
        initialize();
    }

    @Test
    void prodWritesOneJsonObjectPerLineWithTheRequestAndTheJob(CapturedOutput output) throws Exception {
        initialize("real", "prod");
        MDC.put("requestId", "rid-json-1");
        MDC.put("jobId", "job-json-1");
        MDC.put("attempt", "2");
        MDC.put("traceId", TRACE_ID);
        MDC.put("spanId", "00f067aa0ba902b7");
        MDC.put("notLogged", "x");
        log.error("Internal error: GET /api/v1/jobs/job-json-1\nsecond line", new IllegalStateException("boom"));

        List<String> lines = output.getOut().lines().filter(line -> line.contains("rid-json-1")).toList();
        assertThat(lines).as("one line, stack included").hasSize(1);
        JsonNode line = JSON.readTree(lines.get(0));
        assertThat(line.path("requestId").asText()).isEqualTo("rid-json-1");
        assertThat(line.path("jobId").asText()).isEqualTo("job-json-1");
        assertThat(line.path("attempt").asText()).isEqualTo("2");
        // the trace (W8, 2026-11-26): Grafana's Loki data source links "traceId" to Tempo
        assertThat(line.path("traceId").asText()).isEqualTo(TRACE_ID);
        assertThat(line.path("spanId").asText()).isEqualTo("00f067aa0ba902b7");
        assertThat(line.has("notLogged")).as("only the five MDC keys").isFalse();
        assertThat(line.path("level").asText()).isEqualTo("ERROR");
        assertThat(line.path("logger_name").asText()).isEqualTo(LoggingFormatTest.class.getName());
        assertThat(line.path("message").asText()).isEqualTo("Internal error: GET /api/v1/jobs/job-json-1\nsecond line");
        assertThat(line.path("stack_trace").asText()).startsWith("java.lang.IllegalStateException: boom")
                .contains("\tat " + LoggingFormatTest.class.getName());
        String timestamp = line.path("@timestamp").asText();
        assertThat(timestamp).as("UTC").endsWith("Z");
        assertThat(Instant.parse(timestamp)).isNotNull();
        assertThat(line.has("@version")).isFalse();
        assertThat(line.has("level_value")).isFalse();
    }

    @Test
    void everyOtherProfileWritesTextWithTheRequestAndTheJob(CapturedOutput output) throws Exception {
        initialize("real");
        MDC.put("requestId", "rid-text-1");
        MDC.put("jobId", "job-text-1");
        MDC.put("traceId", TRACE_ID);
        MDC.put("spanId", "00f067aa0ba902b7");
        log.warn("text line one");
        MDC.clear();
        log.warn("text line two");

        // trace= since 2026-11-26 (W8); Boot's own [traceId-spanId] block stays off (logging.pattern.correlation empty)
        String one = output.getOut().lines().filter(line -> line.contains("text line one")).findFirst().orElseThrow();
        assertThat(one).doesNotStartWith("{").contains("WARN [rid=rid-text-1 job=job-text-1 trace=" + TRACE_ID + "]");
        assertThat(one.split(TRACE_ID, -1)).as("the trace id once").hasSize(2);
        String two = output.getOut().lines().filter(line -> line.contains("text line two")).findFirst().orElseThrow();
        assertThat(two).contains("WARN [rid= job= trace=]").doesNotContain("[                ");
    }

    /**
     * What LoggingApplicationListener does at startup, for these profiles: logback-spring.xml (the only logback file on
     * the classpath) with the main application.properties.
     */
    private static void initialize(String... profiles) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addLast(new PropertiesPropertySource("application.properties",
                PropertiesLoaderUtils.loadAllProperties("application.properties")));
        // What Boot's LogCorrelationEnvironmentPostProcessor adds when tracing is on (Micrometer Tracing on the classpath)
        Properties tracingOn = new Properties();
        tracingOn.setProperty("logging.expect-correlation-id", "true");
        environment.getPropertySources().addLast(new PropertiesPropertySource("logCorrelation", tracingOn));
        environment.setActiveProfiles(profiles);
        LOGGING.cleanUp();
        LOGGING.beforeInitialize();
        LOGGING.initialize(new LoggingInitializationContext(environment), null, null);
    }
}
