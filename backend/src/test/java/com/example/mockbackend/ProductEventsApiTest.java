package com.example.mockbackend;

import com.example.mockbackend.domain.ProductEventRow;
import com.example.mockbackend.repository.ProductEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Product events (docs/METRICS.md §6, REVIEW_CHECKLIST #24): the public POST /api/v1/events the Unity app and the share
 * viewer send. A bad batch is refused whole (400, 413); a bad event inside a good batch is dropped and counted, never a
 * 400; values outside the per-event list are never stored (no free text, paths, tokens or addresses).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class ProductEventsApiTest {
    static final String INSTALL = "5f0c7a52-1d3b-4f43-9e64-0a9a7f1b2c3d";
    static final String JOB = "aaaaaaaa-0000-4000-8000-000000000001";
    static final Instant T0 = Instant.parse("2026-11-26T01:00:00Z");

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    ProductEventRepository events;

    @Autowired
    MeterRegistry meters;

    @BeforeEach
    void clean() {
        events.deleteAll();
    }

    @Test
    void aValidBatchIsStoredWithTheServerSideFieldsAndOneLogLine(CapturedOutput output) throws Exception {
        String session = UUID.randomUUID().toString();
        double viewsBefore = accepted("screen_view", "android");
        ObjectNode batch = batch(session, "android",
                event("screen_view", 0).put("screen", "home"),
                withProps(event("photos_selected", 1).put("screen", "home"), "{\"source\":\"gallery\",\"count\":3}"),
                withProps(event("generate_tap", 2).put("screen", "confirm"), "{\"mode\":\"new\",\"photoCount\":3}"),
                withProps(event("model_ready", 3).put("screen", "progress").put("jobId", JOB),
                        "{\"cached\":false,\"loadMs\":812,\"waitMs\":61000}"));

        mvc.perform(send(batch))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(4))
                .andExpect(jsonPath("$.dropped").value(0));

        List<ProductEventRow> rows = stored();
        assertThat(rows).extracting(ProductEventRow::getName)
                .containsExactly("screen_view", "photos_selected", "generate_tap", "model_ready");
        ProductEventRow ready = rows.get(3);
        assertThat(ready.getInstallId()).isEqualTo(INSTALL);
        assertThat(ready.getSessionId()).isEqualTo(session);
        assertThat(ready.getAppVersion()).isEqualTo("0.4.0");
        assertThat(ready.getPlatform()).isEqualTo("android");
        assertThat(ready.getAt()).isEqualTo(T0.plusSeconds(3));
        assertThat(ready.getSeq()).isEqualTo(3);
        assertThat(ready.getJobId()).isEqualTo(JOB);
        assertThat(ready.getScreen()).isEqualTo("progress");
        assertThat(ready.getEnv()).isEqualTo("local");
        assertThat(ready.getReceivedAt()).isNotNull();
        assertThat(objectMapper.readTree(ready.getPropsJson()))
                .isEqualTo(objectMapper.readTree("{\"cached\":false,\"loadMs\":812,\"waitMs\":61000}"));
        assertThat(rows.get(0).getPropsJson()).as("no props, no JSON").isNull();
        assertThat(accepted("screen_view", "android") - viewsBefore).isEqualTo(1.0);

        // One INFO line per batch, not per event, and no installation or session id in the log.
        String log = output.getOut();
        String line = "Product events: 4 accepted, 0 dropped (platform android, app 0.4.0)";
        assertThat(log).contains(line);
        assertThat(log.split("Product events: ", -1).length).as("every 'Product events:' line is the batch line")
                .isEqualTo(log.split(java.util.regex.Pattern.quote(line), -1).length);
        assertThat(log).doesNotContain(INSTALL).doesNotContain(session);
    }

    @Test
    void unknownNamesAndBrokenEventsAreDroppedAndCountedNotRefused() throws Exception {
        double unknownBefore = dropped("unknown_name");
        double invalidBefore = dropped("invalid");
        ObjectNode batch = batch(UUID.randomUUID().toString(), "android",
                event("screen_view", 0).put("screen", "home"),
                event("purchase_completed", 1),                       // not one of the 12 names
                event("photo_saved", 2).put("seq", -1),               // seq must be >= 0
                event("photo_saved", 3).put("at", "yesterday"),        // not an ISO 8601 instant
                event("photo_saved", 4).put("seq", "4"),               // a string is not a number
                event("screen_view", 5),                               // screen_view needs its screen
                event("fur_toggle", 6).put("name", "FUR_TOGGLE"));     // names are case-sensitive

        mvc.perform(send(batch))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(1))
                .andExpect(jsonPath("$.dropped").value(6));

        assertThat(stored()).extracting(ProductEventRow::getName).containsExactly("screen_view");
        assertThat(dropped("unknown_name") - unknownBefore).isEqualTo(2.0);
        assertThat(dropped("invalid") - invalidBefore).isEqualTo(4.0);
    }

    @Test
    void aBadBatchIsRefusedWholeWith400() throws Exception {
        String session = UUID.randomUUID().toString();
        ObjectNode fiftyOne = batch(session, "android");
        ArrayNode list = (ArrayNode) fiftyOne.get("events");
        for (int i = 0; i < 51; i++) {
            list.add(event("photo_saved", i));
        }
        List<String> bodies = List.of(
                fiftyOne.toString(),
                batch(session, "android").toString(),                                              // no events
                batch(session, "ios", event("photo_saved", 0)).toString(),                          // unknown platform
                batch(session, "android", event("photo_saved", 0)).put("installId", "device-1234").toString(),
                batch("not-a-session", "android", event("photo_saved", 0)).toString(),
                batch(session, "android", event("photo_saved", 0)).put("appVersion", "1.0 (build 7)").toString(),
                batch(session, "android", event("photo_saved", 0)).put("appVersion", "1".repeat(33)).toString(),
                batch(session, "android", event("photo_saved", 0)).put("events", "photo_saved").toString(),
                "{\"installId\":",
                "[]",
                "");
        for (String body : bodies) {
            mvc.perform(post("/api/v1/events").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        }
        assertThat(events.count()).as("nothing stored").isZero();

        // Fifty is the limit, not an error.
        ObjectNode fifty = batch(session, "android");
        for (int i = 0; i < 50; i++) {
            ((ArrayNode) fifty.get("events")).add(event("photo_saved", i));
        }
        mvc.perform(send(fifty)).andExpect(status().isAccepted()).andExpect(jsonPath("$.accepted").value(50));
    }

    @Test
    void aBodyOver32KilobytesIs413AndStoresNothing() throws Exception {
        ObjectNode batch = batch(UUID.randomUUID().toString(), "android", event("photo_saved", 0));
        batch.put("padding", "x".repeat(32 * 1024));   // an unknown field: ignored when small, but it counts for the size
        byte[] body = batch.toString().getBytes(StandardCharsets.UTF_8);
        assertThat(body.length).isGreaterThan(32 * 1024);

        mvc.perform(post("/api/v1/events").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error.code").value("PAYLOAD_TOO_LARGE"));
        assertThat(events.count()).isZero();

        ObjectNode small = batch(UUID.randomUUID().toString(), "android", event("photo_saved", 0));
        small.put("padding", "x".repeat(30 * 1024));
        mvc.perform(post("/api/v1/events").contentType(MediaType.APPLICATION_JSON).content(small.toString()))
                .andExpect(status().isAccepted());
    }

    @Test
    void personalLookingFieldsAndValuesOutsideTheListAreNeverStored() throws Exception {
        double badBefore = dropped("bad_prop");
        ObjectNode screenView = event("screen_view", 0).put("screen", "home")
                .put("ip", "203.0.113.7").put("url", "https://beside.example/s/q3Zr8yT0bWm1xK4vL9pN2A");
        ObjectNode photos = withProps(event("photos_selected", 1).put("screen", "secret-screen").put("jobId", "C:/storage/uploads/1"),
                "{\"source\":\"gallery\",\"count\":2,\"fileName\":\"IMG_0001.jpg\",\"serverUrl\":\"http://192.168.0.10:8080\"}");
        ObjectNode failed = withProps(event("job_failed_shown", 2),
                "{\"code\":\"Server said: /home/beside/storage boom\",\"retryable\":true}");
        ObjectNode tap = withProps(event("generate_tap", 3), "{\"mode\":\"new\",\"photoCount\":11}");     // over 10
        ObjectNode fur = withProps(event("fur_toggle", 4), "{\"on\":\"yes\"}");                            // not a boolean
        ObjectNode over = withProps(event("start_over", 5), "{\"from\":\"settings\"}");                   // not preview|ar|error
        ObjectNode notAnObject = event("photo_saved", 6).put("props", "IMG_0001.jpg");
        ObjectNode batch = batch(UUID.randomUUID().toString(), "android", screenView, photos, failed, tap, fur, over, notAnObject);
        batch.put("ip", "203.0.113.7").put("deviceId", "android-id-1234");

        mvc.perform(send(batch))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(7))
                .andExpect(jsonPath("$.dropped").value(0));

        List<ProductEventRow> rows = stored();
        assertThat(objectMapper.readTree(rows.get(1).getPropsJson()))
                .isEqualTo(objectMapper.readTree("{\"source\":\"gallery\",\"count\":2}"));
        assertThat(rows.get(1).getScreen()).as("not one of the screens").isNull();
        assertThat(rows.get(1).getJobId()).as("not a UUID").isNull();
        assertThat(objectMapper.readTree(rows.get(2).getPropsJson())).isEqualTo(objectMapper.readTree("{\"retryable\":true}"));
        assertThat(objectMapper.readTree(rows.get(3).getPropsJson())).isEqualTo(objectMapper.readTree("{\"mode\":\"new\"}"));
        assertThat(rows.get(4).getPropsJson()).isNull();
        assertThat(rows.get(5).getPropsJson()).isNull();
        assertThat(rows.get(6).getPropsJson()).isNull();
        for (ProductEventRow row : rows) {
            String all = String.join("|", row.getName(), String.valueOf(row.getScreen()), String.valueOf(row.getJobId()),
                    String.valueOf(row.getPropsJson()), row.getAppVersion(), row.getPlatform());
            assertThat(all).doesNotContain("203.0.113.7", "q3Zr8yT0bWm1xK4vL9pN2A", "IMG_0001", "192.168.0.10",
                    "storage", "android-id", "secret");
        }
        // fileName, serverUrl, screen, jobId, code, photoCount, on, from and the non-object props: 9 values dropped.
        assertThat(dropped("bad_prop") - badBefore).isEqualTo(9.0);
    }

    @Test
    void theSavedDogAndAppErrorEventsTheLibraryAndCameraScreensAndEveryPlacementAreStored() throws Exception {
        // 2026-11-27 (openapi v1.3 후보): app_error, saved_dog_open, saved_dog_change, screens library·camera,
        // model_placed's again·source
        double badBefore = dropped("bad_prop");
        double errorsBefore = accepted("app_error", "android");
        ObjectNode batch = batch(UUID.randomUUID().toString(), "android",
                event("screen_view", 0).put("screen", "library"),
                event("screen_view", 1).put("screen", "camera"),
                withProps(event("saved_dog_open", 2).put("screen", "library"),
                        "{\"ok\":true,\"from\":\"ar\",\"count\":3,\"ageDays\":2,\"loadMs\":840}"),
                withProps(event("saved_dog_change", 3).put("screen", "library"), "{\"action\":\"replaced\",\"count\":5}"),
                withProps(event("model_placed", 4).put("screen", "ar"),
                        "{\"method\":\"tap\",\"secSinceArEnter\":4,\"again\":true,\"source\":\"library\"}"),
                // sig as text (a message would be text too): only that value goes
                withProps(event("app_error", 5).put("screen", "ar"), "{\"kind\":\"exception\",\"sig\":\"NullReferenceException at Foo\"}"),
                withProps(event("app_error", 6), "{\"kind\":\"error\",\"sig\":2147483647}"),
                // outside the lists: action, from, count over 50, source, kind
                withProps(event("saved_dog_change", 7), "{\"action\":\"renamed\",\"count\":51}"),
                withProps(event("saved_dog_open", 8), "{\"ok\":\"yes\",\"from\":\"settings\",\"ageDays\":3651,\"loadMs\":600001}"),
                withProps(event("model_placed", 9), "{\"source\":\"url\",\"again\":1}"),
                withProps(event("app_error", 10), "{\"kind\":\"warning\",\"sig\":-1}"));

        mvc.perform(send(batch))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(11))
                .andExpect(jsonPath("$.dropped").value(0));

        List<ProductEventRow> rows = stored();
        assertThat(rows).extracting(ProductEventRow::getScreen).containsExactly(
                "library", "camera", "library", "library", "ar", "ar", null, null, null, null, null);
        assertThat(objectMapper.readTree(rows.get(2).getPropsJson())).isEqualTo(
                objectMapper.readTree("{\"ok\":true,\"from\":\"ar\",\"count\":3,\"ageDays\":2,\"loadMs\":840}"));
        assertThat(objectMapper.readTree(rows.get(3).getPropsJson()))
                .isEqualTo(objectMapper.readTree("{\"action\":\"replaced\",\"count\":5}"));
        assertThat(objectMapper.readTree(rows.get(4).getPropsJson())).isEqualTo(
                objectMapper.readTree("{\"method\":\"tap\",\"secSinceArEnter\":4,\"again\":true,\"source\":\"library\"}"));
        assertThat(objectMapper.readTree(rows.get(5).getPropsJson())).isEqualTo(objectMapper.readTree("{\"kind\":\"exception\"}"));
        assertThat(objectMapper.readTree(rows.get(6).getPropsJson()))
                .isEqualTo(objectMapper.readTree("{\"kind\":\"error\",\"sig\":2147483647}"));
        for (int i = 7; i <= 10; i++) {
            assertThat(rows.get(i).getPropsJson()).as("row %d", i).isNull();
        }
        // sig text, action, count, ok, from, ageDays, loadMs, source, again, kind, sig -1: 11 values
        assertThat(dropped("bad_prop") - badBefore).isEqualTo(11.0);
        assertThat(accepted("app_error", "android") - errorsBefore).isEqualTo(3.0);
    }

    @Test
    void aBatchSentAgainIsStoredOnce() throws Exception {
        double duplicateBefore = dropped("duplicate");
        ObjectNode batch = batch(UUID.randomUUID().toString(), "android",
                event("screen_view", 0).put("screen", "home"), event("photo_saved", 1), event("photo_saved", 1));
        mvc.perform(send(batch))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(2))
                .andExpect(jsonPath("$.dropped").value(1));
        // The app did not get the 202 and sends the same lines again.
        mvc.perform(send(batch))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(0))
                .andExpect(jsonPath("$.dropped").value(3));
        assertThat(events.count()).isEqualTo(2);
        assertThat(dropped("duplicate") - duplicateBefore).isEqualTo(4.0);
    }

    @Test
    void theShareViewerSendsAsWebAndAnyContentTypeIsRead() throws Exception {
        ObjectNode batch = batch(UUID.randomUUID().toString(), "web",
                event("screen_view", 0).put("screen", "share"),
                withProps(event("animation_play", 1).put("screen", "share"), "{\"clip\":\"TailWag\"}"));
        batch.put("appVersion", "web");
        double before = accepted("animation_play", "web");
        mvc.perform(post("/api/v1/events").contentType(MediaType.TEXT_PLAIN).content(batch.toString())
                        .header("Sec-Fetch-Site", "same-origin").header("Origin", "http://localhost"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(2));
        assertThat(accepted("animation_play", "web") - before).isEqualTo(1.0);
    }

    /** Like every other v1 write (CrossSiteWriteFilter): a page of another site cannot post into the tables. */
    @Test
    void writesFromAPageOfAnotherSiteAreRefused() throws Exception {
        ObjectNode batch = batch(UUID.randomUUID().toString(), "web", event("screen_view", 0).put("screen", "share"));
        List<MockHttpServletRequestBuilder> crossSite = List.of(
                send(batch).header("Sec-Fetch-Site", "cross-site"),
                send(batch).header("Sec-Fetch-Site", "same-site"),
                send(batch).header("Origin", "https://evil.example"),
                send(batch).header("Origin", "null"));
        for (MockHttpServletRequestBuilder request : crossSite) {
            mvc.perform(request)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        }
        assertThat(events.count()).isZero();
        mvc.perform(send(batch).header("Sec-Fetch-Site", "same-origin")).andExpect(status().isAccepted());
        mvc.perform(send(batch)).andExpect(status().isAccepted()).andExpect(jsonPath("$.dropped").value(1)); // the app: no header
        assertThat(events.count()).isEqualTo(1);
    }

    @Test
    void readingTheCollectorIsNotAnEndpoint() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/events"))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    // ------------------------------------------------------------------ helpers

    private List<ProductEventRow> stored() {
        return events.findAll().stream().sorted(Comparator.comparing(ProductEventRow::getId)).toList();
    }

    private double accepted(String name, String platform) {
        return meters.get("beside.app.events").tag("name", name).tag("platform", platform).counter().count();
    }

    private double dropped(String reason) {
        return meters.get("beside.app.events.dropped").tag("reason", reason).counter().count();
    }

    private MockHttpServletRequestBuilder send(ObjectNode batch) {
        return post("/api/v1/events").contentType(MediaType.APPLICATION_JSON).content(batch.toString());
    }

    ObjectNode batch(String session, String platform, ObjectNode... list) {
        ObjectNode batch = objectMapper.createObjectNode()
                .put("installId", INSTALL)
                .put("sessionId", session)
                .put("appVersion", "0.4.0")
                .put("platform", platform);
        ArrayNode array = batch.putArray("events");
        for (ObjectNode event : list) {
            array.add(event);
        }
        return batch;
    }

    ObjectNode event(String name, int seq) {
        return objectMapper.createObjectNode().put("name", name).put("at", T0.plusSeconds(seq).toString()).put("seq", seq);
    }

    ObjectNode withProps(ObjectNode event, String propsJson) throws Exception {
        event.set("props", objectMapper.readTree(propsJson));
        return event;
    }
}
