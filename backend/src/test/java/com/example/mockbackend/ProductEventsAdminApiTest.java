package com.example.mockbackend;

import com.example.mockbackend.domain.Job;
import com.example.mockbackend.domain.JobStatus;
import com.example.mockbackend.domain.ProductEventRow;
import com.example.mockbackend.repository.JobRepository;
import com.example.mockbackend.repository.ProductEventRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Reading product events back (docs/METRICS.md §6.2·§6.4, openapi adminEvents·adminFunnel): the CSV export with the
 * documented columns and the session funnel of the new-model flow. Team-internal; Caddy Basic auth guards it.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProductEventsAdminApiTest {
    /** openapi adminEvents, written out on purpose. */
    private static final String HEADER =
            "receivedAt,env,platform,appVersion,installId,sessionId,seq,at,name,screen,jobId,props";
    private static final String INSTALL = "5f0c7a52-1d3b-4f43-9e64-0a9a7f1b2c3d";
    private static final String JOB = "aaaaaaaa-0000-4000-8000-000000000001";
    private static final Instant T0 = Instant.parse("2026-11-26T01:00:00Z");

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    ProductEventRepository events;

    @Autowired
    JobRepository jobRepository;

    @BeforeEach
    void clean() {
        events.deleteAll();
    }

    @Test
    void theCsvHasTheDocumentedColumnsAndPropsAsJsonText() throws Exception {
        String session = UUID.randomUUID().toString();
        send(session, "android",
                event("screen_view", 0, 0).put("screen", "home"),
                withProps(event("photos_selected", 1, 4).put("screen", "home"), "{\"source\":\"gallery\",\"count\":2}"),
                withProps(event("model_ready", 2, 70).put("jobId", JOB), "{\"cached\":true,\"loadMs\":5,\"waitMs\":0}"));

        MvcResult csv = mvc.perform(get("/api/v1/admin/events").param("format", "csv"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"product_events.csv\""))
                .andReturn();
        String[] lines = csv.getResponse().getContentAsString(StandardCharsets.UTF_8).split("\r\n");
        assertThat(lines).hasSize(4);
        assertThat(lines[0]).isEqualTo(HEADER);
        assertThat(lines[1]).endsWith(",local,android,0.4.0," + INSTALL + "," + session + ",0," + T0 + ",screen_view,home,,");
        assertThat(lines[2]).endsWith(",photos_selected,home,,\"{\"\"source\"\":\"\"gallery\"\",\"\"count\"\":2}\"");
        assertThat(lines[3]).contains(",model_ready,," + JOB + ",");

        JsonNode rows = json(mvc.perform(get("/api/v1/admin/events"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn());
        assertThat(rows).hasSize(3);
        List<String> names = new ArrayList<>();
        rows.get(0).fieldNames().forEachRemaining(names::add);
        assertThat(String.join(",", names)).isEqualTo(HEADER);
        assertThat(rows.get(1).path("props")).isEqualTo(objectMapper.readTree("{\"source\":\"gallery\",\"count\":2}"));
        assertThat(rows.get(0).path("props").isNull()).isTrue();
        assertThat(rows.get(2).path("jobId").asText()).isEqualTo(JOB);
    }

    @Test
    void fromAndToFilterOnTheTimeTheServerReceivedTheEvent() throws Exception {
        Instant before = Instant.now().minusSeconds(1);
        Instant phoneClock = Instant.parse("2020-01-01T00:00:00Z");   // a phone whose clock is years off
        send(UUID.randomUUID().toString(), "web",
                event("screen_view", 0, 0).put("screen", "share").put("at", phoneClock.toString()));
        assertThat(json(mvc.perform(get("/api/v1/admin/events").param("from", before.toString())).andReturn())).hasSize(1);
        assertThat(json(mvc.perform(get("/api/v1/admin/events").param("to", before.toString())).andReturn())).isEmpty();
        // The app's own clock (at) does not decide the range.
        assertThat(json(mvc.perform(get("/api/v1/admin/events")
                .param("from", phoneClock.minusSeconds(60).toString())
                .param("to", phoneClock.plusSeconds(60).toString())).andReturn())).isEmpty();

        for (String[] bad : new String[][]{{"format", "xml"}, {"from", "yesterday"}, {"to", "2026-13-01"}}) {
            mvc.perform(get("/api/v1/admin/events").param(bad[0], bad[1]))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        }
        mvc.perform(get("/api/v1/admin/funnel").param("from", "2026-11-02T00:00:00Z").param("to", "2026-11-01T00:00:00Z"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theFunnelCountsSessionsThatReachEachStepInOrder() throws Exception {
        // S1 goes all the way.
        send(UUID.randomUUID().toString(), "android",
                event("screen_view", 0, 0).put("screen", "home"),
                withProps(event("photos_selected", 1, 10), "{\"source\":\"gallery\",\"count\":2}"),
                withProps(event("generate_tap", 2, 20), "{\"mode\":\"new\",\"photoCount\":2}"),
                event("model_ready", 3, 80),
                event("screen_view", 4, 90).put("screen", "ar"),
                withProps(event("model_placed", 5, 100), "{\"method\":\"tap\",\"secSinceArEnter\":10}"));
        // S2 closes the gallery without a photo (count 0 is not a selection).
        send(UUID.randomUUID().toString(), "android",
                event("screen_view", 0, 0).put("screen", "home"),
                withProps(event("photos_selected", 1, 5), "{\"source\":\"gallery\",\"count\":0}"));
        // S3 stops after the tap.
        send(UUID.randomUUID().toString(), "android",
                event("screen_view", 0, 0).put("screen", "home"),
                withProps(event("photos_selected", 1, 30), "{\"source\":\"camera\",\"count\":1}"),
                event("generate_tap", 2, 40));
        // S4 resumes a saved job: it skipped the photo step, so only home counts.
        send(UUID.randomUUID().toString(), "android",
                event("screen_view", 0, 0).put("screen", "home"),
                withProps(event("generate_tap", 1, 5), "{\"mode\":\"resume\"}"),
                event("model_ready", 2, 60),
                event("screen_view", 3, 70).put("screen", "ar"),
                event("model_placed", 4, 80));
        // S5: a model_ready before the tap does not count; the one after it does (sent in two batches, out of order).
        String s5 = UUID.randomUUID().toString();
        send(s5, "android",
                withProps(event("photos_selected", 2, 20), "{\"source\":\"gallery\",\"count\":3}"),
                event("generate_tap", 3, 30),
                event("model_ready", 4, 200));
        send(s5, "android",
                event("screen_view", 0, 0).put("screen", "home"),
                event("model_ready", 1, 1));
        // S6 only watched a shared model: a session, but not in the app funnel.
        send(UUID.randomUUID().toString(), "web", event("screen_view", 0, 0).put("screen", "share"));

        JsonNode funnel = json(mvc.perform(get("/api/v1/admin/funnel"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn());
        assertThat(funnel.path("sessions").asLong()).isEqualTo(6);
        assertThat(funnel.path("from").isNull()).isTrue();
        JsonNode steps = funnel.path("steps");
        assertThat(steps).hasSize(6);
        List<String> names = new ArrayList<>();
        steps.forEach(step -> names.add(step.path("step").asText()));
        assertThat(names).containsExactly("home", "photos_selected", "generate_tap", "model_ready", "ar", "model_placed");
        assertThat(steps.get(0).path("event").asText()).isEqualTo("screen_view(screen=home)");
        assertThat(steps.get(4).path("event").asText()).isEqualTo("screen_view(screen=ar)");

        long[] sessions = {5, 3, 3, 2, 1, 1};
        long[] dropped = {0, 2, 0, 1, 1, 0};
        double[] conversion = {100, 60, 60, 40, 20, 20};
        Double[] fromPrev = {null, 60.0, 100.0, 200.0 / 3, 50.0, 100.0};
        Double[] median = {null, 20.0, 10.0, 60.0, 10.0, 10.0};
        for (int i = 0; i < 6; i++) {
            JsonNode step = steps.get(i);
            assertThat(step.path("sessions").asLong()).as("sessions of %s", names.get(i)).isEqualTo(sessions[i]);
            assertThat(step.path("droppedSessions").asLong()).as("dropped at %s", names.get(i)).isEqualTo(dropped[i]);
            assertThat(step.path("conversionPct").asDouble()).as("conversion of %s", names.get(i)).isCloseTo(conversion[i], within(0.001));
            if (fromPrev[i] == null) {
                assertThat(step.path("fromPrevPct").isNull()).isTrue();
            } else {
                assertThat(step.path("fromPrevPct").asDouble()).as("from previous, %s", names.get(i)).isCloseTo(fromPrev[i], within(0.001));
            }
            if (median[i] == null) {
                assertThat(step.path("medianSecondsFromPrev").isNull()).isTrue();
            } else {
                assertThat(step.path("medianSecondsFromPrev").asDouble()).as("median of %s", names.get(i)).isCloseTo(median[i], within(0.001));
            }
        }
        // every session's last event came in just now: it may still be in use, so no drop analysis yet
        assertThat(funnel.path("lastScreens")).isEmpty();
        assertThat(funnel.path("dropReasons")).isEmpty();
        assertThat(funnel.path("librarySteps")).hasSize(5);
        assertThat(funnel.path("jobs").path("created").isIntegralNumber()).isTrue();
    }

    @Test
    void droppedSessionsShowTheirLastScreenAndReasonsAndTheLibraryHasItsOwnFunnel() throws Exception {
        Instant old = Instant.now().minus(Duration.ofHours(2));
        Instant fresh = Instant.now();
        // D1 closes the gallery without a photo.
        store(old, "D1", row("screen_view", 0, "home", null), row("photos_selected", 1, "home", "{\"source\":\"gallery\",\"count\":0}"));
        // D2 gives up in the queue.
        store(old, "D2", row("screen_view", 0, "home", null), row("photos_selected", 1, null, "{\"count\":1}"),
                row("generate_tap", 2, null, "{\"mode\":\"new\"}"), row("screen_view", 3, "progress", null),
                row("job_cancel_tap", 4, "progress", "{\"stage\":\"queued\",\"elapsedSec\":40}"));
        // D3 sees a failure and the app throws.
        store(old, "D3", row("screen_view", 0, "home", null), row("photos_selected", 1, null, "{\"count\":2}"),
                row("generate_tap", 2, null, null), row("screen_view", 3, "error", null),
                row("job_failed_shown", 4, "error", "{\"code\":\"INFERENCE_TIMEOUT\",\"retryable\":true}"),
                row("app_error", 5, "error", "{\"kind\":\"exception\",\"sig\":12345}"));
        // D4 goes all the way with its own job.
        store(old, "D4", row("screen_view", 0, "home", null), row("photos_selected", 1, null, "{\"count\":1}"),
                row("generate_tap", 2, null, null), row("model_ready", 3, null, null), row("screen_view", 4, "ar", null),
                row("model_placed", 5, "ar", "{\"method\":\"tap\",\"source\":\"job\"}"));
        // D5 opens a saved dog from the library and places it: the library funnel, not the main one.
        store(old, "D5", row("screen_view", 0, "home", null), row("screen_view", 5, "library", null),
                row("saved_dog_open", 10, "library", "{\"ok\":true,\"from\":\"home\",\"count\":2}"),
                row("screen_view", 12, "preview", null), row("screen_view", 15, "ar", null),
                row("model_placed", 20, "ar", "{\"method\":\"center\",\"source\":\"library\",\"again\":false}"));
        // D6 is like D1 but its last event came in just now: maybe still choosing, not a drop yet.
        store(fresh, "D6", row("screen_view", 0, "home", null), row("photos_selected", 1, "home", "{\"count\":0}"));
        // D7 makes a model but only places a saved dog: the main funnel stops before its last step.
        store(old, "D7", row("screen_view", 0, "home", null), row("photos_selected", 1, null, "{\"count\":1}"),
                row("generate_tap", 2, null, null), row("model_ready", 3, null, null), row("screen_view", 4, "ar", null),
                row("model_placed", 5, "ar", "{\"source\":\"library\"}"), row("app_error", 6, "ar", "{\"kind\":\"exception\"}"));
        // D8 cannot open its saved dog.
        store(old, "D8", row("screen_view", 0, "home", null), row("screen_view", 1, "library", null),
                row("saved_dog_open", 2, "library", "{\"ok\":false,\"from\":\"home\",\"count\":1}"));

        JsonNode funnel = json(mvc.perform(get("/api/v1/admin/funnel")).andExpect(status().isOk()).andReturn());
        assertThat(longs(funnel.path("steps"), "sessions")).containsExactly(8L, 4L, 4L, 2L, 2L, 1L);
        assertThat(longs(funnel.path("steps"), "droppedSessions")).containsExactly(0L, 4L, 0L, 2L, 0L, 1L);

        // D1, D2, D3, D5, D7, D8 (not D4 that finished, not D6 that is too fresh)
        assertThat(funnel.path("lastScreens").toString()).isEqualTo(objectMapper.readTree("""
                [{"screen":"ar","sessions":2},{"screen":"error","sessions":1},{"screen":"home","sessions":1},
                 {"screen":"library","sessions":1},{"screen":"progress","sessions":1}]""").toString());
        assertThat(funnel.path("dropReasons").toString()).isEqualTo(objectMapper.readTree("""
                [{"event":"app_error","key":"kind","value":"exception","sessions":2},
                 {"event":"job_cancel_tap","key":"stage","value":"queued","sessions":1},
                 {"event":"job_failed_shown","key":"code","value":"INFERENCE_TIMEOUT","sessions":1},
                 {"event":"photos_selected","key":"count","value":"0","sessions":1}]""").toString());

        JsonNode library = funnel.path("librarySteps");
        List<String> names = new ArrayList<>();
        library.forEach(step -> names.add(step.path("step").asText()));
        assertThat(names).containsExactly("home", "library", "saved_dog_open", "ar", "model_placed");
        assertThat(library.get(2).path("event").asText()).isEqualTo("saved_dog_open(ok=true)");
        assertThat(library.get(4).path("event").asText()).isEqualTo("model_placed(source=library)");
        assertThat(longs(library, "sessions")).containsExactly(8L, 2L, 1L, 1L, 1L);
        assertThat(longs(library, "droppedSessions")).containsExactly(0L, 6L, 1L, 0L, 0L);
        assertThat(library.get(4).path("medianSecondsFromPrev").asDouble()).isCloseTo(5.0, within(0.001));
    }

    @Test
    void jobCompletionCountsTheJobsCreatedInTheRangeByWhereTheyAreNow() throws Exception {
        Instant from = Instant.parse("2001-01-01T00:00:00Z");
        List<String> ids = new ArrayList<>();
        try {
            for (String state : List.of("COMPLETED", "COMPLETED", "COMPLETED", "FAILED:INFERENCE_FAILED", "FAILED:CANCELED",
                    "PENDING", "PROCESSING")) {
                ids.add(job(from.plusSeconds(ids.size() * 60L), state));
            }
            ids.add(job(from.plus(Duration.ofDays(2)), "COMPLETED"));   // created after the range
            JsonNode jobs = json(mvc.perform(get("/api/v1/admin/funnel")
                            .param("from", from.toString()).param("to", from.plus(Duration.ofDays(1)).toString()))
                    .andExpect(status().isOk()).andReturn()).path("jobs");
            assertThat(jobs.path("created").asLong()).isEqualTo(7);
            assertThat(jobs.path("completed").asLong()).isEqualTo(3);
            assertThat(jobs.path("failed").asLong()).as("a cancel is not a failure").isEqualTo(1);
            assertThat(jobs.path("canceled").asLong()).isEqualTo(1);
            assertThat(jobs.path("inProgress").asLong()).isEqualTo(2);
            assertThat(jobs.path("completionPct").asDouble()).as("3 of the 6 not canceled").isCloseTo(50.0, within(0.001));
        } finally {
            jobRepository.deleteAllById(ids);
        }
    }

    @Test
    void anEmptyRangeHasZeroSessionsAndNoMedians() throws Exception {
        JsonNode funnel = json(mvc.perform(get("/api/v1/admin/funnel")
                        .param("from", "2026-11-01T00:00:00Z").param("to", "2026-11-02T00:00:00Z"))
                .andExpect(status().isOk()).andReturn());
        assertThat(funnel.path("sessions").asLong()).isZero();
        assertThat(funnel.path("from").asText()).isEqualTo("2026-11-01T00:00:00Z");
        assertThat(funnel.path("steps")).hasSize(6).allSatisfy(step -> {
            assertThat(step.path("sessions").asLong()).isZero();
            assertThat(step.path("conversionPct").asDouble()).isZero();
            assertThat(step.path("medianSecondsFromPrev").isNull()).isTrue();
        });
        assertThat(funnel.path("lastScreens")).isEmpty();
        assertThat(funnel.path("dropReasons")).isEmpty();
        assertThat(funnel.path("librarySteps")).hasSize(5);
        assertThat(funnel.path("jobs").path("created").asLong()).isZero();
        assertThat(funnel.path("jobs").path("completionPct").asDouble()).isZero();
    }

    // ------------------------------------------------------------------ helpers

    /** One stored row as the collector would write it, received at {@code receivedAt}. */
    private record Row(String name, int second, String screen, String propsJson) {
    }

    private static Row row(String name, int second, String screen, String propsJson) {
        return new Row(name, second, screen, propsJson);
    }

    /** Stores a session's rows directly, so their receivedAt can lie in the past (the drop analysis waits 30 minutes). */
    private void store(Instant receivedAt, String label, Row... rows) {
        String session = UUID.nameUUIDFromBytes(label.getBytes(StandardCharsets.UTF_8)).toString();
        int seq = 0;
        for (Row row : rows) {
            events.save(ProductEventRow.of(receivedAt, INSTALL, session, "0.5.0", "android", row.name(),
                    T0.plusSeconds(row.second()), seq++, null, row.screen(), row.propsJson(), "local"));
        }
    }

    /** A job created at {@code createdAt} that is now in {@code state} (STATUS or FAILED:CODE). */
    private String job(Instant createdAt, String state) {
        String[] parts = state.split(":");
        Job job = new Job();
        job.setId(UUID.randomUUID().toString());
        job.setStatus(JobStatus.valueOf(parts[0]));
        job.setErrorCode(parts.length > 1 ? parts[1] : null);
        job.setCreatedAt(createdAt);
        job.setUpdatedAt(createdAt);
        job.setQueuedAt(createdAt);
        job.setAttempt(1);
        return jobRepository.save(job).getId();
    }

    private static List<Long> longs(JsonNode array, String field) {
        List<Long> values = new ArrayList<>();
        array.forEach(node -> values.add(node.path(field).asLong()));
        return values;
    }

    private void send(String session, String platform, ObjectNode... list) throws Exception {
        ObjectNode batch = objectMapper.createObjectNode()
                .put("installId", INSTALL)
                .put("sessionId", session)
                .put("appVersion", "0.4.0")
                .put("platform", platform);
        ArrayNode array = batch.putArray("events");
        for (ObjectNode event : list) {
            array.add(event);
        }
        mvc.perform(post("/api/v1/events").contentType(MediaType.APPLICATION_JSON).content(batch.toString()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.dropped").value(0));
    }

    private ObjectNode event(String name, int seq, int second) {
        return objectMapper.createObjectNode().put("name", name).put("at", T0.plusSeconds(second).toString()).put("seq", seq);
    }

    private ObjectNode withProps(ObjectNode event, String propsJson) throws Exception {
        event.set("props", objectMapper.readTree(propsJson));
        return event;
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
