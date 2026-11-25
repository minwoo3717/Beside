package com.example.mockbackend;

import com.example.mockbackend.domain.ShareLink;
import com.example.mockbackend.repository.ShareLinkRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The server-side product funnel through the API (docs/METRICS.md §5.1): every refused upload of POST /api/v1/jobs
 * counted once under its code (the service's own checks and the missing part Spring raises first), an accepted one
 * only in beside.jobs.created, and share links counted when made and when the viewer opens them, by why a 404 was a
 * 404. The 413 of the multipart limits needs a real connection: ObservabilityEndpointTest.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProductFunnelMetricsTest {
    private static final SecureRandom RANDOM = new SecureRandom();

    @TempDir
    static Path storage;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("storage.upload.dir", () -> storage.resolve("uploads").toString());
        registry.add("storage.result.sample", () -> storage.resolve("sample-dog.glb").toString());
        registry.add("mock.worker.pending-ms", () -> "100");
        registry.add("mock.worker.processing-ms", () -> "100");
    }

    @BeforeAll
    static void writeSample() throws IOException {
        Files.write(storage.resolve("sample-dog.glb"), "glTF funnel sample".getBytes(StandardCharsets.UTF_8));
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    ShareLinkRepository shareLinks;

    @Autowired
    MeterRegistry meters;

    @Test
    void everyRefusedUploadIsCountedOnceByItsCodeAndAnAcceptedOneIsNot() throws Exception {
        Map<String, Double> before = rejected();
        double created = meters.get("beside.jobs.created").counter().count();

        // No 'photos' part: Spring refuses before the service runs (UploadRejectionCounter).
        mvc.perform(multipart("/api/v1/jobs").file(new MockMultipartFile("other", "dog.jpg", "image/jpeg", new byte[4])))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("NO_PHOTOS"));
        MockMultipartFile[] eleven = new MockMultipartFile[11];
        for (int i = 0; i < eleven.length; i++) {
            eleven[i] = jpeg("dog-" + i + ".jpg");
        }
        upload(null, eleven).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("TOO_MANY_PHOTOS"));
        upload(null, new MockMultipartFile("photos", "dog.gif", "image/gif", new byte[]{1, 2}))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("UNSUPPORTED_IMAGE_TYPE"));
        upload(null, new MockMultipartFile("photos", "empty.jpg", "image/jpeg", new byte[0]))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        upload("k".repeat(129), jpeg("dog.jpg"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        // Accepted, then replayed with the same key: one job, nothing refused.
        String key = "funnel-" + UUID.randomUUID();
        upload(key, jpeg("dog.jpg")).andExpect(status().isAccepted());
        upload(key, jpeg("dog.jpg")).andExpect(status().isAccepted());

        assertThat(delta(before, rejected())).containsExactlyInAnyOrderEntriesOf(Map.of(
                "NO_PHOTOS", 1.0, "TOO_MANY_PHOTOS", 1.0, "UNSUPPORTED_IMAGE_TYPE", 1.0, "INVALID_REQUEST", 2.0));
        assertThat(meters.get("beside.jobs.created").counter().count() - created).as("the replay is not a new job")
                .isEqualTo(1.0);
    }

    @Test
    void sharesAreCountedWhenMadeAndWhenTheViewerOpensThemByWhyTheyDidNotOpen() throws Exception {
        String jobId = completedJob();
        double sharesBefore = meters.get("beside.share.created").counter().count();
        Map<String, Double> before = views();

        String token = json(mvc.perform(post("/api/v1/jobs/{id}/shares", jobId)).andExpect(status().isCreated())
                .andReturn()).path("token").asText();
        mvc.perform(get("/api/v1/shares/{token}", token)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/shares/{token}", token)).andExpect(status().isOk());
        // The GLB and the revoke reach the link too, but only the viewer's info call is a view.
        mvc.perform(get("/api/v1/shares/{token}/asset", token)).andExpect(status().isOk());
        mvc.perform(delete("/api/v1/shares/{token}", token)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/shares/{token}", token)).andExpect(status().isNotFound());

        String expired = saveLink(jobId, Instant.now().minus(Duration.ofDays(8)), Instant.now().minusSeconds(1));
        mvc.perform(get("/api/v1/shares/{token}", expired)).andExpect(status().isNotFound());
        String orphan = saveLink(UUID.randomUUID().toString(), Instant.now(), Instant.now().plus(Duration.ofDays(1)));
        for (String notFound : new String[]{randomToken(), "not-a-share-token", orphan}) {
            mvc.perform(get("/api/v1/shares/{token}", notFound))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("SHARE_NOT_FOUND"));
        }

        assertThat(meters.get("beside.share.created").counter().count() - sharesBefore).isEqualTo(1.0);
        assertThat(delta(before, views())).containsExactlyInAnyOrderEntriesOf(Map.of(
                "ok", 2.0, "revoked", 1.0, "expired", 1.0, "not_found", 3.0));
        assertThat(meters.find("beside.share.views").counters()).as("the token is never a tag")
                .allSatisfy(counter -> assertThat(counter.getId().getTags())
                        .allSatisfy(tag -> assertThat(tag.getValue()).isNotEqualTo(token)));
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Double> rejected() {
        Map<String, Double> counts = new TreeMap<>();
        for (Counter counter : meters.get("beside.jobs.upload.rejected").counters()) {
            counts.put(counter.getId().getTag("code"), counter.count());
        }
        return counts;
    }

    private Map<String, Double> views() {
        Map<String, Double> counts = new TreeMap<>();
        for (Counter counter : meters.get("beside.share.views").counters()) {
            counts.put(counter.getId().getTag("outcome"), counter.count());
        }
        return counts;
    }

    /** What grew between two readings, leaving out what did not. */
    private static Map<String, Double> delta(Map<String, Double> before, Map<String, Double> after) {
        Map<String, Double> grown = new HashMap<>();
        after.forEach((name, count) -> {
            double change = count - before.getOrDefault(name, 0.0);
            if (change != 0) {
                grown.put(name, change);
            }
        });
        return grown;
    }

    private org.springframework.test.web.servlet.ResultActions upload(String key, MockMultipartFile... files)
            throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart("/api/v1/jobs");
        for (MockMultipartFile file : files) {
            request.file(file);
        }
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mvc.perform(request);
    }

    private static MockMultipartFile jpeg(String name) {
        return new MockMultipartFile("photos", name, "image/jpeg", new byte[4]);
    }

    private String completedJob() throws Exception {
        String jobId = json(upload(null, jpeg("share-dog.jpg")).andExpect(status().isAccepted()).andReturn())
                .path("jobId").asText();
        await().pollInterval(Duration.ofMillis(50)).atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(json(mvc.perform(get("/api/v1/jobs/{id}", jobId))
                        .andExpect(status().isOk()).andReturn()).path("status").asText()).isEqualTo("COMPLETED"));
        return jobId;
    }

    private String saveLink(String jobId, Instant createdAt, Instant expiresAt) {
        String token = randomToken();
        shareLinks.save(ShareLink.issue(token, jobId, createdAt, expiresAt));
        return token;
    }

    private static String randomToken() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private JsonNode json(MvcResult result) throws IOException {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }
}
