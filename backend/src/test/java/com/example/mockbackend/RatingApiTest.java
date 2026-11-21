package com.example.mockbackend;

import com.example.mockbackend.domain.Job;
import com.example.mockbackend.domain.JobRating;
import com.example.mockbackend.domain.JobRun;
import com.example.mockbackend.domain.JobStatus;
import com.example.mockbackend.domain.RatingScores;
import com.example.mockbackend.domain.RunDetails;
import com.example.mockbackend.domain.StoredUpload;
import com.example.mockbackend.repository.JobRatingRepository;
import com.example.mockbackend.repository.JobRepository;
import com.example.mockbackend.repository.JobRunRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.util.UriUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Human quality ratings (docs/METRICS.md §4·§4.1, tag quality) and their admin export. Jobs and job_runs rows are
 * written straight into the database, as AdminApiTest does, so no worker runs and every timestamp is known.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RatingApiTest {
    /** docs/METRICS.md §4.1, written out on purpose: the export must follow the document, not a constant in the code. */
    private static final List<String> RATING_COLUMNS = List.of(
            "jobId", "modelVersion", "rater", "faceShape", "ears", "bodyProportion", "furColor", "pattern",
            "textureArtifacts", "comment", "updatedAt", "workerType", "passthrough");
    private static final List<String> ITEMS = List.of(
            "faceShape", "ears", "bodyProportion", "furColor", "pattern", "textureArtifacts");
    private static final Instant T0 = Instant.parse("2026-11-11T00:00:00Z");
    private static final String JOB_A = "aaaaaaaa-0000-4000-8000-000000000001";
    private static final String JOB_B = "bbbbbbbb-0000-4000-8000-000000000002";
    private static final String JOB_C = "cccccccc-0000-4000-8000-000000000003";
    private static final String JOB_D = "dddddddd-0000-4000-8000-000000000004";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JobRepository jobs;

    @Autowired
    JobRunRepository runs;

    @Autowired
    JobRatingRepository ratings;

    @BeforeEach
    void clean() {
        ratings.deleteAll();
        runs.deleteAll();
        jobs.deleteAll();
    }

    // ------------------------------------------------------------------ PUT / GET per job

    @Test
    void onlyCompletedJobsCanBeRated() throws Exception {
        saveJob(JOB_A, JobStatus.PENDING);
        saveJob(JOB_B, JobStatus.FAILED);
        for (String jobId : List.of(JOB_A, JOB_B)) {
            mvc.perform(putRating(jobId, "alice", body(4, 4, 4, 4, 4, 4, null)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("JOB_NOT_COMPLETED"))
                    .andExpect(jsonPath("$.error.jobId").value(jobId));
            mvc.perform(get("/api/v1/jobs/{id}/ratings", jobId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items").isEmpty())
                    .andExpect(jsonPath("$.average").value((Object) null));
        }
        mvc.perform(putRating(JOB_C, "alice", body(4, 4, 4, 4, 4, 4, null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("JOB_NOT_FOUND"))
                .andExpect(jsonPath("$.error.jobId").value(JOB_C));
        mvc.perform(get("/api/v1/jobs/{id}/ratings", JOB_C))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("JOB_NOT_FOUND"));
        assertThat(ratings.count()).isZero();
    }

    @Test
    void scoresOutsideOneToFiveBadNamesAndMalformedBodiesAreInvalidRequest() throws Exception {
        saveJob(JOB_A, JobStatus.COMPLETED);
        List<String> badBodies = List.of(
                body(0, 3, 3, 3, 3, 3, null),
                body(3, 6, 3, 3, 3, 3, null),
                body(3, 3, -1, 3, 3, 3, null),
                "{\"faceShape\":3,\"ears\":3,\"bodyProportion\":3,\"furColor\":3,\"pattern\":3}",          // textureArtifacts missing
                "{\"faceShape\":3,\"ears\":3,\"bodyProportion\":3,\"furColor\":3,\"pattern\":null,\"textureArtifacts\":3}",
                "{\"faceShape\":3.5,\"ears\":3,\"bodyProportion\":3,\"furColor\":3,\"pattern\":3,\"textureArtifacts\":3}",
                "{\"faceShape\":3.0,\"ears\":3,\"bodyProportion\":3,\"furColor\":3,\"pattern\":3,\"textureArtifacts\":3}",
                "{\"faceShape\":\"3\",\"ears\":3,\"bodyProportion\":3,\"furColor\":3,\"pattern\":3,\"textureArtifacts\":3}",
                "{\"faceShape\":true,\"ears\":3,\"bodyProportion\":3,\"furColor\":3,\"pattern\":3,\"textureArtifacts\":3}",
                body(3, 3, 3, 3, 3, 3, "x".repeat(501)),
                "{\"faceShape\":3,\"ears\":3,\"bodyProportion\":3,\"furColor\":3,\"pattern\":3,\"textureArtifacts\":3,\"comment\":5}",
                "[3,3,3,3,3,3]",
                "not json",
                "");
        for (String bad : badBodies) {
            assertInvalid(putRating(JOB_A, "alice", bad));
        }
        String good = body(3, 3, 3, 3, 3, 3, null);
        for (String rater : List.of(" ", "가".repeat(41), "a;b", "..", "a\\b", "bell\u0007")) {
            assertInvalid(putRating(JOB_A, rater, good));
        }
        assertThat(ratings.count()).as("nothing stored by a rejected request").isZero();

        mvc.perform(putRating(JOB_A, "가".repeat(40), body(3, 3, 3, 3, 3, 3, "가".repeat(500))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rater").value("가".repeat(40)));
    }

    @Test
    void sameRaterOverwritesAndTheAverageFollows() throws Exception {
        saveJob(JOB_A, JobStatus.COMPLETED);

        JsonNode first = json(mvc.perform(putRating(JOB_A, "alice", body(5, 4, 3, 2, 1, 5, "귀가 조금 작다")))
                .andExpect(status().isOk()).andReturn());
        assertThat(first.path("jobId").asText()).isEqualTo(JOB_A);
        assertThat(first.path("rater").asText()).isEqualTo("alice");
        assertScores(first, 5, 4, 3, 2, 1, 5);
        assertThat(first.path("comment").asText()).isEqualTo("귀가 조금 작다");
        Instant createdAt = Instant.parse(first.path("createdAt").asText());
        assertThat(Instant.parse(first.path("updatedAt").asText())).isEqualTo(createdAt);

        JsonNode bob = json(mvc.perform(putRating(JOB_A, "  bob ", body(3, 3, 3, 3, 3, 3, null)))
                .andExpect(status().isOk()).andReturn());
        assertThat(bob.path("rater").asText()).as("surrounding spaces are trimmed").isEqualTo("bob");
        assertThat(bob.path("comment").isNull()).isTrue();

        JsonNode again = json(mvc.perform(putRating(JOB_A, "alice",
                        "{\"faceShape\":1,\"ears\":1,\"bodyProportion\":1,\"furColor\":1,\"pattern\":1,\"textureArtifacts\":4,"
                                + "\"comment\":\"\",\"unknownField\":true}"))
                .andExpect(status().isOk()).andReturn());
        assertScores(again, 1, 1, 1, 1, 1, 4);
        assertThat(again.path("comment").isNull()).as("an empty comment is stored as null").isTrue();
        assertThat(Instant.parse(again.path("createdAt").asText())).as("createdAt stays").isEqualTo(createdAt);
        assertThat(Instant.parse(again.path("updatedAt").asText())).isAfterOrEqualTo(createdAt);
        assertThat(ratings.count()).as("one row per job and rater").isEqualTo(2);

        mvc.perform(putRating(JOB_A, "Carol", body(4, 2, 5, 2, 3, 3, null))).andExpect(status().isOk());

        JsonNode list = json(mvc.perform(get("/api/v1/jobs/{id}/ratings", JOB_A))
                .andExpect(status().isOk()).andReturn());
        List<String> raters = new ArrayList<>();
        list.path("items").forEach(item -> raters.add(item.path("rater").asText()));
        assertThat(raters).as("rater name order").containsExactly("Carol", "alice", "bob");
        assertScores(list.path("items").get(1), 1, 1, 1, 1, 1, 4);

        JsonNode average = list.path("average");
        assertThat(average.path("raters").asInt()).isEqualTo(3);
        assertThat(average.path("faceShape").asDouble()).isCloseTo((4 + 1 + 3) / 3.0, within(1e-9));
        assertThat(average.path("ears").asDouble()).isCloseTo((2 + 1 + 3) / 3.0, within(1e-9));
        assertThat(average.path("bodyProportion").asDouble()).isCloseTo((5 + 1 + 3) / 3.0, within(1e-9));
        assertThat(average.path("furColor").asDouble()).isCloseTo((2 + 1 + 3) / 3.0, within(1e-9));
        assertThat(average.path("pattern").asDouble()).isCloseTo((3 + 1 + 3) / 3.0, within(1e-9));
        assertThat(average.path("textureArtifacts").asDouble()).isCloseTo((3 + 4 + 3) / 3.0, within(1e-9));
    }

    // ------------------------------------------------------------------ admin export

    @Test
    void adminCsvHasTheDocumentedColumnsAndTheLastRunOfEachJob() throws Exception {
        saveJob(JOB_A, JobStatus.COMPLETED);
        run(JOB_A, 1, "real", "INFERENCE_FAILED", T0.minusSeconds(60), details(false, "old-model"));
        run(JOB_A, 2, "real", null, T0.minusSeconds(30), details(false, "animallift,\"v2\""));
        saveJob(JOB_B, JobStatus.COMPLETED);
        run(JOB_B, 1, "mock", null, T0.minusSeconds(20), RunDetails.NONE);
        saveJob(JOB_C, JobStatus.COMPLETED);
        run(JOB_C, 1, "real", null, T0.minusSeconds(10), details(true, "sample"));
        saveJob(JOB_D, JobStatus.COMPLETED); // finished before job_runs existed: no run row

        rate(JOB_A, "alice", T0.plusSeconds(1), new RatingScores(5, 4, 3, 2, 1, 5), "귀, \"모양\"\n좋음");
        rate(JOB_B, "bob", T0.plusSeconds(2), new RatingScores(3, 3, 3, 3, 3, 3), null);
        rate(JOB_C, "alice", T0.plusSeconds(3), new RatingScores(1, 1, 1, 1, 1, 1), null);
        rate(JOB_D, "bob", T0.plusSeconds(4), new RatingScores(2, 2, 2, 2, 2, 2), null);
        rate(JOB_A, "bob", T0.plusSeconds(4), new RatingScores(4, 4, 4, 4, 4, 4), "ok");

        MvcResult result = mvc.perform(get("/api/v1/admin/ratings").param("format", "csv"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"job_ratings.csv\""))
                .andReturn();
        MediaType type = MediaType.parseMediaType(result.getResponse().getContentType());
        assertThat(type.isCompatibleWith(MediaType.parseMediaType("text/csv"))).isTrue();
        assertThat(type.getCharset()).isEqualTo(StandardCharsets.UTF_8);
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).startsWith(String.join(",", RATING_COLUMNS) + "\r\n");
        assertThat(body.substring(body.indexOf("\r\n") + 2)).as("rows in updatedAt order, ties by jobId then rater; passthrough C left out")
                .isEqualTo(JOB_A + ",\"animallift,\"\"v2\"\"\",alice,5,4,3,2,1,5,\"귀, \"\"모양\"\"\n좋음\",2026-11-11T00:00:01Z,real,false\r\n"
                        + JOB_B + ",,bob,3,3,3,3,3,3,,2026-11-11T00:00:02Z,mock,\r\n"
                        + JOB_A + ",\"animallift,\"\"v2\"\"\",bob,4,4,4,4,4,4,ok,2026-11-11T00:00:04Z,real,false\r\n"
                        + JOB_D + ",,bob,2,2,2,2,2,2,,2026-11-11T00:00:04Z,,\r\n");

        JsonNode rows = json(mvc.perform(get("/api/v1/admin/ratings"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().doesNotExist("Content-Disposition"))
                .andReturn());
        assertThat(rows).hasSize(4);
        assertThat(fieldNames(rows.get(0))).as("JSON names = CSV header").containsExactlyElementsOf(RATING_COLUMNS);
        assertThat(rows.get(0).path("modelVersion").asText()).as("attempt 2, not 1").isEqualTo("animallift,\"v2\"");

        assertThat(jobIdsAndRaters(get("/api/v1/admin/ratings").param("includePassthrough", "true")))
                .containsExactly(JOB_A + "/alice", JOB_B + "/bob", JOB_C + "/alice", JOB_A + "/bob", JOB_D + "/bob");
        assertThat(jobIdsAndRaters(get("/api/v1/admin/ratings")
                .param("from", T0.plusSeconds(2).toString()).param("to", T0.plusSeconds(4).toString())))
                .as("from inclusive, to exclusive, on updatedAt").containsExactly(JOB_B + "/bob");
    }

    @Test
    void summaryAveragesEveryRatingOfAModelVersion() throws Exception {
        String baseline = "animallift-baseline@abc";
        String improved = "animallift-square-pad@def";
        saveJob(id(1), JobStatus.COMPLETED);
        run(id(1), 1, "real", null, T0, details(false, baseline));
        saveJob(id(2), JobStatus.COMPLETED);
        run(id(2), 1, "real", null, T0, details(false, baseline));
        saveJob(id(3), JobStatus.COMPLETED);
        run(id(3), 1, "real", null, T0, details(false, improved));
        saveJob(id(4), JobStatus.COMPLETED);
        run(id(4), 1, "mock", null, T0, RunDetails.NONE);
        saveJob(id(5), JobStatus.COMPLETED);
        run(id(5), 1, "real", null, T0, details(true, baseline)); // passthrough: out by default

        rate(id(1), "alice", T0.plusSeconds(1), new RatingScores(2, 3, 4, 5, 1, 2), null);
        rate(id(1), "bob", T0.plusSeconds(2), new RatingScores(4, 3, 2, 1, 5, 4), null);
        rate(id(2), "alice", T0.plusSeconds(3), new RatingScores(3, 3, 3, 3, 3, 3), null);
        rate(id(3), "carol", T0.plusSeconds(4), new RatingScores(5, 5, 5, 5, 5, 5), null);
        rate(id(4), "alice", T0.plusSeconds(5), new RatingScores(1, 2, 3, 4, 5, 1), null);
        rate(id(5), "alice", T0.plusSeconds(6), new RatingScores(1, 1, 1, 1, 1, 1), null);

        JsonNode groups = json(mvc.perform(get("/api/v1/admin/ratings/summary")).andExpect(status().isOk()).andReturn())
                .path("groups");
        assertThat(groups).hasSize(3);

        JsonNode none = groups.get(0);
        assertThat(none.path("modelVersion").asText()).isEqualTo("(none)");
        assertGroup(none, 1, 1, 1, 1, 2, 3, 4, 5, 1);

        JsonNode base = groups.get(1);
        assertThat(base.path("modelVersion").asText()).isEqualTo(baseline);
        // Mean of the three rating rows (not of the two per-job means): faceShape (2+4+3)/3 = 3.
        assertGroup(base, 2, 3, 2, 3.0, 3.0, 3.0, 3.0, 3.0, 3.0);

        JsonNode better = groups.get(2);
        assertThat(better.path("modelVersion").asText()).isEqualTo(improved);
        assertGroup(better, 1, 1, 1, 5, 5, 5, 5, 5, 5);

        JsonNode withPassthrough = json(mvc.perform(get("/api/v1/admin/ratings/summary").param("includePassthrough", "true"))
                .andExpect(status().isOk()).andReturn()).path("groups").get(1);
        assertGroup(withPassthrough, 3, 4, 2, 10 / 4.0, 10 / 4.0, 10 / 4.0, 10 / 4.0, 10 / 4.0, 10 / 4.0);

        assertThat(json(mvc.perform(get("/api/v1/admin/ratings/summary").param("from", T0.plusSeconds(100).toString()))
                .andExpect(status().isOk()).andReturn()).path("groups")).isEmpty();
    }

    @Test
    void adminJobGivesTheDetailPageTheAttemptAndTheLastRunsModelVersion() throws Exception {
        saveJob(JOB_A, JobStatus.COMPLETED);
        Job retried = jobs.findById(JOB_A).orElseThrow();
        retried.setAttempt(2);
        jobs.save(retried);
        run(JOB_A, 1, "real", "INFERENCE_FAILED", T0.minusSeconds(60), details(false, "old-model"));
        run(JOB_A, 2, "real", null, T0.minusSeconds(30), details(false, "animallift-baseline@abc"));

        mvc.perform(get("/api/v1/admin/jobs/{id}", JOB_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(JOB_A))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.attempt").value(2))
                .andExpect(jsonPath("$.modelVersion").value("animallift-baseline@abc"))
                .andExpect(jsonPath("$.errorCode").value((Object) null));

        saveJob(JOB_B, JobStatus.PENDING);
        mvc.perform(get("/api/v1/admin/jobs/{id}", JOB_B))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modelVersion").value((Object) null))
                .andExpect(jsonPath("$.timings.totalMs").value((Object) null));

        mvc.perform(get("/api/v1/admin/jobs/{id}", JOB_C))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("JOB_NOT_FOUND"))
                .andExpect(jsonPath("$.error.jobId").value(JOB_C));
    }

    @Test
    void invalidAdminParametersAreInvalidRequest() throws Exception {
        List<MockHttpServletRequestBuilder> invalid = List.of(
                get("/api/v1/admin/ratings").param("format", "xml"),
                get("/api/v1/admin/ratings").param("from", "yesterday"),
                get("/api/v1/admin/ratings").param("from", T0.plusSeconds(60).toString()).param("to", T0.toString()),
                get("/api/v1/admin/ratings").param("includePassthrough", "maybe"),
                get("/api/v1/admin/ratings/summary").param("to", "2026-13-01T00:00:00Z"),
                get("/api/v1/admin/ratings/summary").param("includePassthrough", "maybe"));
        for (MockHttpServletRequestBuilder request : invalid) {
            assertInvalid(request);
        }
    }

    // ------------------------------------------------------------------ helpers

    /** The rater is percent-encoded like the page's encodeURIComponent, so ';' and spaces reach the server as text. */
    private static MockHttpServletRequestBuilder putRating(String jobId, String rater, String json) {
        URI uri = URI.create("/api/v1/jobs/" + jobId + "/ratings/" + UriUtils.encode(rater, StandardCharsets.UTF_8));
        return put(uri).contentType(MediaType.APPLICATION_JSON).content(json);
    }

    private String body(int faceShape, int ears, int bodyProportion, int furColor, int pattern, int textureArtifacts,
                        String comment) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("faceShape", faceShape);
        body.put("ears", ears);
        body.put("bodyProportion", bodyProportion);
        body.put("furColor", furColor);
        body.put("pattern", pattern);
        body.put("textureArtifacts", textureArtifacts);
        if (comment != null) {
            body.put("comment", comment);
        }
        return objectMapper.writeValueAsString(body);
    }

    private void assertInvalid(MockHttpServletRequestBuilder request) throws Exception {
        mvc.perform(request)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.message").isNotEmpty());
    }

    private static void assertScores(JsonNode rating, int... expected) {
        for (int i = 0; i < ITEMS.size(); i++) {
            assertThat(rating.path(ITEMS.get(i)).isInt()).as(ITEMS.get(i) + " is an integer").isTrue();
            assertThat(rating.path(ITEMS.get(i)).asInt()).as(ITEMS.get(i)).isEqualTo(expected[i]);
        }
    }

    private static void assertGroup(JsonNode group, long jobs, long ratingRows, long raters, double... averages) {
        assertThat(group.path("jobs").asLong()).as("jobs").isEqualTo(jobs);
        assertThat(group.path("ratings").asLong()).as("ratings").isEqualTo(ratingRows);
        assertThat(group.path("raters").asLong()).as("raters").isEqualTo(raters);
        for (int i = 0; i < ITEMS.size(); i++) {
            assertThat(group.path(ITEMS.get(i)).asDouble()).as(ITEMS.get(i)).isCloseTo(averages[i], within(1e-9));
        }
    }

    private void rate(String jobId, String rater, Instant at, RatingScores scores, String comment) {
        ratings.saveAndFlush(JobRating.create(jobId, rater, scores, comment, at));
    }

    /** A jobs row as a worker leaves it (no worker is dispatched). */
    private void saveJob(String id, JobStatus status) {
        Job job = new Job();
        job.setId(id);
        job.setStatus(status);
        job.setCreatedAt(T0.minusSeconds(3600));
        job.setUpdatedAt(T0.minusSeconds(3600));
        job.setQueuedAt(T0.minusSeconds(3600));
        job.setAttempt(1);
        job.setErrorCode(status == JobStatus.FAILED ? "INFERENCE_FAILED" : null);
        job.setUploads(List.of(new StoredUpload("dog.jpg", 2048, "not-exposed/dog.jpg")));
        jobs.save(job);
    }

    /** One finished run as JobFinisher records it. */
    private void run(String jobId, int attempt, String workerType, String errorCode, Instant finishedAt, RunDetails details) {
        Job job = new Job();
        job.setId(jobId);
        job.setStatus(errorCode == null ? JobStatus.COMPLETED : JobStatus.FAILED);
        job.setErrorCode(errorCode);
        job.setCreatedAt(finishedAt.minusSeconds(5));
        job.setQueuedAt(finishedAt.minusSeconds(5));
        job.setProcessingStartedAt(finishedAt.minusSeconds(4));
        job.setFinishedAt(finishedAt);
        job.setAttempt(attempt);
        job.setUploadMs(12L);
        job.setUploads(List.of(new StoredUpload("dog.jpg", 2048, "not-exposed/dog.jpg")));
        runs.saveAndFlush(JobRun.of(job, finishedAt, workerType, details));
    }

    private static RunDetails details(Boolean passthrough, String modelVersion) {
        return new RunDetails(1000L, 9000L, null, null, null, null, 4096L, passthrough, modelVersion);
    }

    private static String id(int n) {
        return String.format("00000000-0000-4000-8000-%012d", n);
    }

    private List<String> jobIdsAndRaters(MockHttpServletRequestBuilder request) throws Exception {
        JsonNode rows = json(mvc.perform(request).andExpect(status().isOk()).andReturn());
        List<String> keys = new ArrayList<>();
        rows.forEach(row -> keys.add(row.path("jobId").asText() + "/" + row.path("rater").asText()));
        return keys;
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
