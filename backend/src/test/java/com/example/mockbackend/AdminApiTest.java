package com.example.mockbackend;

import com.example.mockbackend.domain.Job;
import com.example.mockbackend.domain.JobRun;
import com.example.mockbackend.domain.JobStatus;
import com.example.mockbackend.domain.RunDetails;
import com.example.mockbackend.domain.StoredUpload;
import com.example.mockbackend.repository.JobRepository;
import com.example.mockbackend.repository.JobRunRepository;
import com.example.mockbackend.service.InferenceStatusSource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Admin API (PLAN S5): measurement export and dashboard data. Rows are written straight into job_runs and jobs with
 * known timings, so CSV lines and percentiles can be checked against numbers computed by hand.
 * Caddy Basic auth guards these paths in deployment; Spring has no auth code, so there is nothing to test here for it.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminApiTest {
    /** docs/METRICS.md §1.1, written out on purpose: the export must follow the document, not a constant in the code. */
    private static final List<String> METRICS_COLUMNS = List.of(
            "jobId", "attempt", "finishedAt", "workerType", "status", "errorCode",
            "uploadBytes", "uploadMs", "queuedMs", "processingMs", "totalMs",
            "inferMs", "gpuPeakMB", "modelParams", "outputVertices", "outputTriangles", "convertMs", "glbBytes",
            "passthrough", "modelVersion");
    private static final Instant T0 = Instant.parse("2026-11-06T00:00:00Z");
    private static final String JOB_A = "aaaaaaaa-0000-4000-8000-000000000001";
    private static final String JOB_B = "bbbbbbbb-0000-4000-8000-000000000002";
    private static final String JOB_C = "cccccccc-0000-4000-8000-000000000003";
    /** What the test InferenceStatusSource answers; null = no GPU information. */
    private static final AtomicReference<Object> INFERENCE = new AtomicReference<>();
    private static final int STORAGE_FILE_BYTES = 1234;

    @TempDir
    static Path storage;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("storage.root.dir", () -> storage.toString());
        registry.add("storage.upload.dir", () -> storage.resolve("uploads").toString());
    }

    @BeforeAll
    static void fillStorage() throws IOException {
        Files.createDirectories(storage.resolve("results"));
        Files.write(storage.resolve("results").resolve("base.glb"), new byte[STORAGE_FILE_BYTES]);
    }

    /** Stands in for the GPU monitor that will implement InferenceStatusSource (PLAN S3). */
    @TestConfiguration
    static class InferenceHook {
        @Bean
        InferenceStatusSource testInferenceStatusSource() {
            return INFERENCE::get;
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JobRepository jobs;

    @Autowired
    JobRunRepository runs;

    @BeforeEach
    void clean() {
        runs.deleteAll();
        jobs.deleteAll();
        INFERENCE.set(null);
    }

    // ------------------------------------------------------------------ runs (export)

    @Test
    void csvHasTheMetricsHeaderAndOneEscapedRowPerRun() throws Exception {
        run(JOB_A, 1, "real", null, T0.plusSeconds(10), 120, 3400L,
                new RunDetails(48210L, 9120L, 312_000_000L, 10342L, 19876L, 850L, 5_242_880L, false, "animallift,\"v2\""));
        run(JOB_B, 1, "mock", "INFERENCE_FAILED", T0.plusSeconds(20), 5, null, RunDetails.NONE);

        MvcResult result = mvc.perform(get("/api/v1/admin/runs").param("format", "csv"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"job_runs.csv\""))
                .andReturn();
        MediaType type = MediaType.parseMediaType(result.getResponse().getContentType());
        assertThat(type.isCompatibleWith(MediaType.parseMediaType("text/csv"))).as("Content-Type %s", type).isTrue();
        assertThat(type.getCharset()).isEqualTo(StandardCharsets.UTF_8);

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        String[] lines = body.split("\r\n", -1);
        assertThat(lines[0]).as("header = docs/METRICS.md §1.1 names in order").isEqualTo(String.join(",", METRICS_COLUMNS));
        // A value with a comma or a quote is quoted and its quotes doubled (RFC 4180); null is an empty cell.
        assertThat(lines[1]).isEqualTo(JOB_A + ",1,2026-11-06T00:00:10Z,real,COMPLETED,,2048,12,120,3400,3520,"
                + "48210,9120,312000000,10342,19876,850,5242880,false,\"animallift,\"\"v2\"\"\"");
        assertThat(lines[2]).isEqualTo(JOB_B + ",1,2026-11-06T00:00:20Z,mock,FAILED,INFERENCE_FAILED,2048,12,5,,5,,,,,,,,,");
        assertThat(lines).as("every line ends with CRLF").hasSize(4);
        assertThat(lines[3]).isEmpty();
        assertThat(body).as("no server paths").doesNotContain("not-exposed");
    }

    @Test
    void csvOfNoRunsIsJustTheHeader() throws Exception {
        String body = mvc.perform(get("/api/v1/admin/runs").param("format", "CSV"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).isEqualTo(String.join(",", METRICS_COLUMNS) + "\r\n");
    }

    @Test
    void jsonIsAnArrayWithTheSameNamesInTheSameOrder() throws Exception {
        run(JOB_A, 1, "real", null, T0.plusSeconds(10), 120, 3400L,
                new RunDetails(48210L, 9120L, null, null, null, null, 5_242_880L, false, "animallift-baseline@abc1234"));

        MvcResult result = mvc.perform(get("/api/v1/admin/runs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().doesNotExist("Content-Disposition"))
                .andReturn();
        JsonNode rows = json(result);
        assertThat(rows.isArray()).isTrue();
        assertThat(rows).hasSize(1);
        JsonNode row = rows.get(0);
        assertThat(fieldNames(row)).as("JSON names = CSV header").containsExactlyElementsOf(METRICS_COLUMNS);
        assertThat(row.path("jobId").asText()).isEqualTo(JOB_A);
        assertThat(row.path("finishedAt").asText()).isEqualTo("2026-11-06T00:00:10Z");
        assertThat(row.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(row.path("errorCode").isNull()).isTrue();
        assertThat(row.path("queuedMs").asLong()).isEqualTo(120);
        assertThat(row.path("processingMs").asLong()).isEqualTo(3400);
        assertThat(row.path("totalMs").asLong()).isEqualTo(3520);
        assertThat(row.path("gpuPeakMB").asLong()).isEqualTo(9120);
        assertThat(row.path("modelParams").isNull()).isTrue();
        assertThat(row.path("passthrough").asBoolean(true)).isFalse();
        assertThat(row.path("modelVersion").asText()).isEqualTo("animallift-baseline@abc1234");
    }

    @Test
    void passthroughRunsAreLeftOutUnlessAskedFor() throws Exception {
        run(JOB_A, 1, "real", null, T0.plusSeconds(1), 10, 20L, details(5L, null, 100L, false, "m"));
        run(JOB_B, 1, "real", null, T0.plusSeconds(2), 10, 20L, details(null, null, 100L, true, "sample"));
        run(JOB_C, 1, "mock", null, T0.plusSeconds(3), 10, 20L, RunDetails.NONE); // passthrough null: not a passthrough

        assertThat(jobIds(get("/api/v1/admin/runs"))).containsExactly(JOB_A, JOB_C);
        assertThat(jobIds(get("/api/v1/admin/runs").param("includePassthrough", "false"))).containsExactly(JOB_A, JOB_C);
        assertThat(jobIds(get("/api/v1/admin/runs").param("includePassthrough", "true"))).containsExactly(JOB_A, JOB_B, JOB_C);

        String csv = mvc.perform(get("/api/v1/admin/runs").param("format", "csv"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv).doesNotContain(JOB_B).contains(JOB_A, JOB_C);
    }

    @Test
    void runsAreFilteredByFinishedAtModelVersionAndWorkerType() throws Exception {
        Instant one = T0.plusSeconds(3600);
        Instant two = T0.plusSeconds(7200);
        Instant three = T0.plusSeconds(10800);
        run(JOB_A, 1, "real", null, one, 10, 20L, details(5L, null, null, false, "v1"));
        run(JOB_B, 1, "real", null, two, 10, 20L, details(5L, null, null, false, "v2"));
        run(JOB_C, 1, "mock", null, three, 10, 20L, RunDetails.NONE);

        assertThat(jobIds(get("/api/v1/admin/runs").param("from", two.toString()))).as("from is inclusive")
                .containsExactly(JOB_B, JOB_C);
        assertThat(jobIds(get("/api/v1/admin/runs").param("to", two.toString()))).as("to is exclusive")
                .containsExactly(JOB_A);
        assertThat(jobIds(get("/api/v1/admin/runs").param("from", one.toString()).param("to", three.toString())))
                .containsExactly(JOB_A, JOB_B);
        assertThat(jobIds(get("/api/v1/admin/runs").param("from", "2026-11-06T10:00:00+09:00"))).as("offset form")
                .containsExactly(JOB_A, JOB_B, JOB_C);
        assertThat(jobIds(get("/api/v1/admin/runs").param("modelVersion", "v2"))).containsExactly(JOB_B);
        assertThat(jobIds(get("/api/v1/admin/runs").param("modelVersion", "(none)"))).containsExactly(JOB_C);
        assertThat(jobIds(get("/api/v1/admin/runs").param("modelVersion", "nope"))).isEmpty();
        assertThat(jobIds(get("/api/v1/admin/runs").param("workerType", "mock"))).containsExactly(JOB_C);
        assertThat(jobIds(get("/api/v1/admin/runs").param("workerType", "real").param("from", two.toString())))
                .containsExactly(JOB_B);
    }

    // ------------------------------------------------------------------ stats

    @Test
    void statsUseNearestRankPercentilesOverCompletedRuns() throws Exception {
        String model = "animallift-baseline@abc";
        // queuedMs, processingMs, inferMs, glbBytes, gpuPeakMB — deliberately unsorted.
        run(id(1), 1, "real", null, T0.plusSeconds(1), 100, 1000L, details(40L, 8000L, 1000L, false, model));
        run(id(2), 1, "real", null, T0.plusSeconds(2), 300, 4000L, details(10L, 9000L, 2000L, false, model));
        run(id(3), 1, "real", null, T0.plusSeconds(3), 200, 2000L, details(30L, null, 3000L, false, model));
        run(id(4), 1, "real", null, T0.plusSeconds(4), 500, 5000L, details(20L, null, 4000L, false, model));
        run(id(5), 1, "real", null, T0.plusSeconds(5), 400, 3000L, details(50L, 10000L, null, false, model));
        // Counted as a run but kept out of the time distribution (METRICS §1.1 exclusion rule).
        run(id(6), 1, "real", "INFERENCE_TIMEOUT", T0.plusSeconds(6), 9, 600_000L, details(null, null, null, null, model));
        // Passthrough: out unless includePassthrough=true.
        run(id(7), 1, "real", null, T0.plusSeconds(7), 1, 1L, details(1L, 1L, 1L, true, model));
        // 20 mock runs without a model version: queuedMs 10..200, processingMs 100..2000.
        for (int i = 1; i <= 20; i++) {
            run(id(100 + i), 1, "mock", null, T0.plusSeconds(100 + i), 10L * i, 100L * i, RunDetails.NONE);
        }

        JsonNode groups = json(mvc.perform(get("/api/v1/admin/stats")).andExpect(status().isOk()).andReturn())
                .path("groups");
        assertThat(groups).hasSize(2);

        JsonNode none = groups.get(0); // "(none)" sorts before letters
        assertThat(none.path("modelVersion").asText()).isEqualTo("(none)");
        assertThat(none.path("workerType").asText()).isEqualTo("mock");
        assertThat(none.path("runs").asLong()).isEqualTo(20);
        assertThat(none.path("completed").asLong()).isEqualTo(20);
        assertThat(none.path("failed").asLong()).isZero();
        assertThat(none.path("successRate").asDouble()).isEqualTo(1.0);
        // n=20: p50 = 10th value, p95 = 19th value (a linear interpolation would give 105 and 190.5).
        assertPercentiles(none.path("queuedMs"), 20, 100L, 190L);
        assertPercentiles(none.path("processingMs"), 20, 1000L, 1900L);
        assertPercentiles(none.path("totalMs"), 20, 1100L, 2090L);
        assertPercentiles(none.path("inferMs"), 0, null, null);
        assertThat(none.path("avgGlbBytes").isNull()).isTrue();
        assertThat(none.path("avgGpuPeakMB").isNull()).isTrue();

        JsonNode real = groups.get(1);
        assertThat(real.path("modelVersion").asText()).isEqualTo(model);
        assertThat(real.path("workerType").asText()).isEqualTo("real");
        assertThat(real.path("runs").asLong()).isEqualTo(6);
        assertThat(real.path("completed").asLong()).isEqualTo(5);
        assertThat(real.path("failed").asLong()).isEqualTo(1);
        assertThat(real.path("successRate").asDouble()).isCloseTo(5.0 / 6.0, within(1e-9));
        // n=5: p50 = 3rd, p95 = 5th of the sorted COMPLETED values; the FAILED 600 000 ms run is not among them.
        assertPercentiles(real.path("queuedMs"), 5, 300L, 500L);
        assertPercentiles(real.path("processingMs"), 5, 3000L, 5000L);
        assertPercentiles(real.path("totalMs"), 5, 3400L, 5500L);
        assertPercentiles(real.path("inferMs"), 5, 30L, 50L);
        assertThat(real.path("avgGlbBytes").asDouble()).isEqualTo(2500.0);
        assertThat(real.path("avgGpuPeakMB").asDouble()).isEqualTo(9000.0);

        JsonNode withPassthrough = json(mvc.perform(get("/api/v1/admin/stats").param("includePassthrough", "true"))
                .andExpect(status().isOk()).andReturn()).path("groups").get(1);
        assertThat(withPassthrough.path("runs").asLong()).isEqualTo(7);
        assertThat(withPassthrough.path("completed").asLong()).isEqualTo(6);
        assertPercentiles(withPassthrough.path("queuedMs"), 6, 200L, 500L);

        JsonNode realOnly = json(mvc.perform(get("/api/v1/admin/stats").param("to", T0.plusSeconds(100).toString()))
                .andExpect(status().isOk()).andReturn()).path("groups");
        assertThat(realOnly).hasSize(1);
        assertThat(realOnly.get(0).path("modelVersion").asText()).isEqualTo(model);
        assertThat(json(mvc.perform(get("/api/v1/admin/stats").param("from", T0.plusSeconds(100_000).toString()))
                .andReturn()).path("groups")).isEmpty();
    }

    // ------------------------------------------------------------------ overview

    @Test
    void overviewCountsJobsByStatusAndReportsQueueAndDisk() throws Exception {
        JsonNode empty = json(mvc.perform(get("/api/v1/admin/overview")).andExpect(status().isOk()).andReturn());
        assertThat(empty.path("statusCounts").path("PENDING").asLong(-1)).isZero();
        assertThat(empty.path("statusCounts").path("PROCESSING").asLong(-1)).isZero();
        assertThat(empty.path("statusCounts").path("COMPLETED").asLong(-1)).isZero();
        assertThat(empty.path("statusCounts").path("FAILED").asLong(-1)).isZero();
        assertThat(empty.path("queue").path("oldestPendingMs").isNull()).isTrue();
        assertThat(empty.path("queue").path("oldestProcessingMs").isNull()).isTrue();

        Instant now = Instant.now();
        saveJob(JOB_A, JobStatus.PENDING, now.minusSeconds(60), null, null, null);
        saveJob(JOB_B, JobStatus.PENDING, now.minusSeconds(5), null, null, null);
        saveJob(JOB_C, JobStatus.PROCESSING, now.minusSeconds(40), now.minusSeconds(30), null, null);
        for (int i = 1; i <= 3; i++) {
            saveJob(id(i), JobStatus.COMPLETED, now.minusSeconds(100), now.minusSeconds(90), now.minusSeconds(80), null);
        }
        saveJob(id(4), JobStatus.FAILED, now.minusSeconds(100), null, now.minusSeconds(99), "INFERENCE_UNAVAILABLE");

        JsonNode overview = json(mvc.perform(get("/api/v1/admin/overview")).andExpect(status().isOk()).andReturn());
        assertThat(overview.path("statusCounts").path("PENDING").asLong()).isEqualTo(2);
        assertThat(overview.path("statusCounts").path("PROCESSING").asLong()).isEqualTo(1);
        assertThat(overview.path("statusCounts").path("COMPLETED").asLong()).isEqualTo(3);
        assertThat(overview.path("statusCounts").path("FAILED").asLong()).isEqualTo(1);
        assertThat(overview.path("queue").path("pending").asLong()).isEqualTo(2);
        assertThat(overview.path("queue").path("processing").asLong()).isEqualTo(1);
        assertThat(overview.path("queue").path("oldestPendingMs").asLong()).isBetween(60_000L, 120_000L);
        assertThat(overview.path("queue").path("oldestProcessingMs").asLong()).isBetween(30_000L, 90_000L);
        assertThat(overview.path("profile").asText()).isEqualTo("mock");
        assertThat(overview.path("workerType").asText()).isEqualTo("mock");
        assertThat(overview.path("generatedAt").asText()).isNotEmpty();

        JsonNode disk = overview.path("disk");
        assertThat(disk.path("usableBytes").asLong()).isPositive();
        assertThat(disk.path("totalBytes").asLong()).isGreaterThanOrEqualTo(disk.path("usableBytes").asLong());
        assertThat(disk.path("storageBytes").asLong()).as("files under storage.root.dir").isEqualTo(STORAGE_FILE_BYTES);
        assertThat(overview.toString()).as("no server paths").doesNotContain(storage.getFileName().toString());
    }

    @Test
    void overviewInferenceIsNullUntilASourceKnowsTheGpuState() throws Exception {
        JsonNode unknown = json(mvc.perform(get("/api/v1/admin/overview")).andExpect(status().isOk()).andReturn());
        assertThat(unknown.has("inference")).isTrue();
        assertThat(unknown.path("inference").isNull()).isTrue();

        INFERENCE.set(Map.of("state", "UP", "device", "cuda"));
        mvc.perform(get("/api/v1/admin/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inference.state").value("UP"))
                .andExpect(jsonPath("$.inference.device").value("cuda"));
    }

    // ------------------------------------------------------------------ recent jobs

    @Test
    void recentJobsAreNewestFirstWithTheLastRunsModelVersion() throws Exception {
        saveJob(JOB_A, JobStatus.FAILED, T0, null, T0.plusSeconds(1), "INFERENCE_UNAVAILABLE");
        run(JOB_A, 1, "real", "INFERENCE_UNAVAILABLE", T0.plusSeconds(1), 1000, null, RunDetails.NONE);

        Job retried = saveJob(JOB_B, JobStatus.COMPLETED, T0.plusSeconds(60), T0.plusSeconds(70), T0.plusSeconds(90), null);
        retried.setAttempt(2);
        jobs.save(retried);
        run(JOB_B, 1, "real", "INFERENCE_FAILED", T0.plusSeconds(65), 5000, null, RunDetails.NONE);
        run(JOB_B, 2, "real", null, T0.plusSeconds(90), 10_000, 20_000L, details(15_000L, null, null, false, "v2"));

        saveJob(JOB_C, JobStatus.PENDING, T0.plusSeconds(120), null, null, null);

        JsonNode items = json(mvc.perform(get("/api/v1/admin/jobs")).andExpect(status().isOk()).andReturn()).path("items");
        assertThat(items).extracting(item -> item.path("id").asText()).containsExactly(JOB_C, JOB_B, JOB_A);

        JsonNode pending = items.get(0);
        assertThat(pending.path("status").asText()).isEqualTo("PENDING");
        assertThat(pending.path("attempt").asInt()).isEqualTo(1);
        assertThat(pending.path("timings").path("processingMs").isNull()).isTrue();
        assertThat(pending.path("timings").path("totalMs").isNull()).isTrue();
        assertThat(pending.path("errorCode").isNull()).isTrue();
        assertThat(pending.path("modelVersion").isNull()).as("no finished run yet").isTrue();

        JsonNode completed = items.get(1);
        assertThat(completed.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(completed.path("attempt").asInt()).isEqualTo(2);
        assertThat(completed.path("modelVersion").asText()).as("from attempt 2, not 1").isEqualTo("v2");
        assertThat(completed.path("errorCode").isNull()).isTrue();
        assertThat(completed.path("timings").path("queuedMs").asLong()).isEqualTo(10_000);
        assertThat(completed.path("timings").path("processingMs").asLong()).isEqualTo(20_000);
        assertThat(completed.path("timings").path("totalMs").asLong()).isEqualTo(30_000);
        assertThat(completed.path("createdAt").asText()).isEqualTo("2026-11-06T00:01:00Z");

        JsonNode failed = items.get(2);
        assertThat(failed.path("errorCode").asText()).isEqualTo("INFERENCE_UNAVAILABLE");
        assertThat(failed.path("modelVersion").isNull()).isTrue();
        assertThat(items.toString()).as("no server paths or photo names").doesNotContain("not-exposed", "dog.jpg");

        JsonNode limited = json(mvc.perform(get("/api/v1/admin/jobs").param("limit", "2")).andExpect(status().isOk()).andReturn())
                .path("items");
        assertThat(limited).extracting(item -> item.path("id").asText()).containsExactly(JOB_C, JOB_B);
    }

    // ------------------------------------------------------------------ 400

    @Test
    void invalidParametersAreInvalidRequest() throws Exception {
        List<MockHttpServletRequestBuilder> invalid = List.of(
                get("/api/v1/admin/runs").param("format", "xml"),
                get("/api/v1/admin/runs").param("from", "yesterday"),
                get("/api/v1/admin/runs").param("to", "2026-13-01T00:00:00Z"),
                get("/api/v1/admin/runs").param("from", T0.plusSeconds(60).toString()).param("to", T0.toString()),
                get("/api/v1/admin/runs").param("includePassthrough", "maybe"),
                get("/api/v1/admin/stats").param("from", "2026-11-06"),
                get("/api/v1/admin/stats").param("includePassthrough", "maybe"),
                get("/api/v1/admin/jobs").param("limit", "0"),
                get("/api/v1/admin/jobs").param("limit", "101"),
                get("/api/v1/admin/jobs").param("limit", "abc"));
        for (MockHttpServletRequestBuilder request : invalid) {
            mvc.perform(request)
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.error.message").isNotEmpty());
        }
    }

    // ------------------------------------------------------------------ page

    @Test
    void dashboardPageIsServedAtAdminWithAndWithoutTrailingSlash() throws Exception {
        mvc.perform(get("/admin")).andExpect(status().isOk()).andExpect(forwardedUrl("/admin/index.html"));
        mvc.perform(get("/admin/")).andExpect(status().isOk()).andExpect(forwardedUrl("/admin/index.html"));
        mvc.perform(get("/admin/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/admin/admin.js")));
        for (String asset : List.of("/admin/admin.js", "/admin/admin-format.js", "/admin/admin.css")) {
            mvc.perform(get(asset)).andExpect(status().isOk());
        }
    }

    // ------------------------------------------------------------------ helpers

    /** One finished run as JobFinisher records it, with exact stage durations ending at {@code finishedAt}. */
    private void run(String jobId, int attempt, String workerType, String errorCode, Instant finishedAt,
                     long queuedMs, Long processingMs, RunDetails details) {
        Instant queuedAt = finishedAt.minusMillis(queuedMs + (processingMs == null ? 0 : processingMs));
        Job job = new Job();
        job.setId(jobId);
        job.setStatus(errorCode == null ? JobStatus.COMPLETED : JobStatus.FAILED);
        job.setErrorCode(errorCode);
        job.setCreatedAt(queuedAt);
        job.setUpdatedAt(finishedAt);
        job.setQueuedAt(queuedAt);
        job.setProcessingStartedAt(processingMs == null ? null : queuedAt.plusMillis(queuedMs));
        job.setFinishedAt(finishedAt);
        job.setAttempt(attempt);
        job.setUploadMs(12L);
        job.setUploads(List.of(new StoredUpload("dog.jpg", 2048, "not-exposed/dog.jpg")));
        runs.saveAndFlush(JobRun.of(job, finishedAt, workerType, details));
    }

    /** A jobs row as a worker leaves it (no worker is dispatched). */
    private Job saveJob(String id, JobStatus status, Instant createdAt, Instant processingStartedAt, Instant finishedAt,
                        String errorCode) {
        Job job = new Job();
        job.setId(id);
        job.setStatus(status);
        job.setCreatedAt(createdAt);
        job.setUpdatedAt(finishedAt != null ? finishedAt : createdAt);
        job.setQueuedAt(createdAt);
        job.setProcessingStartedAt(processingStartedAt);
        job.setFinishedAt(finishedAt);
        job.setErrorCode(errorCode);
        job.setAttempt(1);
        job.setUploads(List.of(new StoredUpload("dog.jpg", 2048, "not-exposed/dog.jpg")));
        return jobs.save(job);
    }

    private static RunDetails details(Long inferMs, Long gpuPeakMB, Long glbBytes, Boolean passthrough, String modelVersion) {
        return new RunDetails(inferMs, gpuPeakMB, null, null, null, null, glbBytes, passthrough, modelVersion);
    }

    private static String id(int n) {
        return String.format("00000000-0000-4000-8000-%012d", n);
    }

    private static void assertPercentiles(JsonNode node, long n, Long p50, Long p95) {
        assertThat(node.path("n").asLong(-1)).as("n").isEqualTo(n);
        if (p50 == null) {
            assertThat(node.path("p50").isNull()).as("p50 null").isTrue();
        } else {
            assertThat(node.path("p50").asLong()).as("p50").isEqualTo(p50);
        }
        if (p95 == null) {
            assertThat(node.path("p95").isNull()).as("p95 null").isTrue();
        } else {
            assertThat(node.path("p95").asLong()).as("p95").isEqualTo(p95);
        }
    }

    private List<String> jobIds(MockHttpServletRequestBuilder request) throws Exception {
        JsonNode rows = json(mvc.perform(request).andExpect(status().isOk()).andReturn());
        List<String> ids = new ArrayList<>();
        rows.forEach(row -> ids.add(row.path("jobId").asText()));
        return ids;
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }
}
