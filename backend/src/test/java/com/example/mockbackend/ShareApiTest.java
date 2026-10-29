package com.example.mockbackend;

import com.example.mockbackend.domain.ShareLink;
import com.example.mockbackend.repository.ShareLinkRepository;
import com.example.mockbackend.service.ShareService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.web.servlet.RequestBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Share links (docs/api/openapi.yaml tag "share") against the mock worker with short delays: only COMPLETED jobs can be
 * shared, the public side never shows the job id, and unknown / expired / revoked / orphaned tokens are one 404.
 * Expired links are stored directly with an expiry in the past instead of waiting for a clock.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ShareApiTest {
    private static final byte[] SAMPLE_GLB = "glTF share sample bytes".getBytes(StandardCharsets.UTF_8);
    /** 128 random bits, base64url without padding. */
    private static final String TOKEN_PATTERN = "[A-Za-z0-9_-]{22}";
    private static final SecureRandom RANDOM = new SecureRandom();

    @TempDir
    static Path storage;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("storage.upload.dir", () -> storage.resolve("uploads").toString());
        registry.add("storage.result.sample", () -> storage.resolve("sample-dog.glb").toString());
        registry.add("mock.worker.pending-ms", () -> "200");
        registry.add("mock.worker.processing-ms", () -> "100");
    }

    @BeforeAll
    static void writeSample() throws IOException {
        Files.write(storage.resolve("sample-dog.glb"), SAMPLE_GLB);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    ShareLinkRepository shareLinks;

    @Autowired
    ShareService shareService;

    @Test
    void sharesOnlyCompletedJobsAndServesInfoAndAssetWithoutTheJobId() throws Exception {
        String jobId = upload("share-dog.jpg");
        mvc.perform(post("/api/v1/jobs/{id}/shares", jobId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("JOB_NOT_COMPLETED"))
                .andExpect(jsonPath("$.error.jobId").value(jobId));
        awaitCompleted(jobId);

        Instant before = Instant.now();
        MvcResult created = mvc.perform(post("/api/v1/jobs/{id}/shares", jobId))
                .andExpect(status().isCreated())
                .andReturn();
        Instant after = Instant.now();
        JsonNode body = json(created);
        String token = body.path("token").asText();
        assertThat(token).matches(TOKEN_PATTERN);
        assertThat(Base64.getUrlDecoder().decode(token)).as("128-bit token").hasSize(16);
        assertThat(body.path("url").asText()).isEqualTo("/s/" + token);
        assertThat(created.getResponse().getHeader("Location")).isEqualTo("/api/v1/shares/" + token);
        Instant expiresAt = Instant.parse(body.path("expiresAt").asText());
        assertThat(expiresAt).as("default share.ttl-days=7")
                .isBetween(before.plus(Duration.ofDays(7)).minusSeconds(1), after.plus(Duration.ofDays(7)).plusSeconds(1));

        String second = json(mvc.perform(post("/api/v1/jobs/{id}/shares", jobId))
                .andExpect(status().isCreated()).andReturn()).path("token").asText();
        assertThat(second).as("every call mints a new token").matches(TOKEN_PATTERN).isNotEqualTo(token);

        MvcResult info = mvc.perform(get("/api/v1/shares/{token}", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asset.url").value("/api/v1/shares/" + token + "/asset"))
                .andExpect(jsonPath("$.asset.bytes").value(SAMPLE_GLB.length))
                .andExpect(jsonPath("$.asset.contentType").value("model/gltf-binary"))
                .andReturn();
        JsonNode infoBody = json(info);
        assertThat(Instant.parse(infoBody.path("expiresAt").asText())).isEqualTo(expiresAt);
        assertThat(Instant.parse(infoBody.path("createdAt").asText())).isBetween(before.minusSeconds(1), after.plusSeconds(1));
        assertThat(infoBody.has("jobId")).isFalse();
        assertThat(info.getResponse().getContentAsString()).as("the public side never shows the job id").doesNotContain(jobId);

        MvcResult asset = mvc.perform(get(infoBody.path("asset").path("url").asText()))
                .andExpect(status().isOk())
                .andExpect(content().contentType("model/gltf-binary"))
                .andExpect(header().string("Cache-Control", "public, max-age=3600"))
                .andExpect(header().longValue("Content-Length", SAMPLE_GLB.length))
                .andExpect(content().bytes(SAMPLE_GLB))
                .andReturn();
        assertThat(asset.getResponse().getHeader("Content-Disposition")).startsWith("inline").doesNotContain(jobId);
    }

    @Test
    void validatesTtlDaysAndCapsTheCacheAgeAtTheExpiry() throws Exception {
        String jobId = completedJob("ttl-dog.jpg");
        for (String bad : List.of("0", "31", "abc")) {
            mvc.perform(post("/api/v1/jobs/{id}/shares", jobId).param("ttlDays", bad))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        }

        Instant before = Instant.now();
        JsonNode oneDay = json(mvc.perform(post("/api/v1/jobs/{id}/shares", jobId).param("ttlDays", "1"))
                .andExpect(status().isCreated()).andReturn());
        assertThat(Instant.parse(oneDay.path("expiresAt").asText()))
                .isBetween(before.plus(Duration.ofDays(1)).minusSeconds(1), Instant.now().plus(Duration.ofDays(1)).plusSeconds(1));
        mvc.perform(post("/api/v1/jobs/{id}/shares", jobId).param("ttlDays", "30"))
                .andExpect(status().isCreated());

        // A link that ends in ten minutes may be cached for ten minutes at most, not an hour.
        String endsSoon = saveLink(jobId, Instant.now().minus(Duration.ofDays(1)), Instant.now().plus(Duration.ofMinutes(10)));
        String cacheControl = mvc.perform(get("/api/v1/shares/{token}/asset", endsSoon))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader("Cache-Control");
        assertThat(cacheControl).matches("public, max-age=\\d+");
        assertThat(Long.parseLong(cacheControl.substring(cacheControl.indexOf('=') + 1))).isBetween(590L, 600L);
    }

    @Test
    void expiredRevokedUnknownMalformedAndOrphanedLinksAreAllShareNotFound() throws Exception {
        String jobId = completedJob("revoke-dog.jpg");

        String expired = saveLink(jobId, Instant.now().minus(Duration.ofDays(8)), Instant.now().minusSeconds(1));
        assertShareNotFound(expired);

        String token = createShare(jobId);
        mvc.perform(get("/api/v1/shares/{token}", token)).andExpect(status().isOk());
        mvc.perform(delete("/api/v1/shares/{token}", token))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        assertShareNotFound(token);

        assertShareNotFound(randomToken());
        assertShareNotFound("not-a-share-token");
        String orphan = saveLink(UUID.randomUUID().toString(), Instant.now(), Instant.now().plus(Duration.ofDays(1)));
        assertShareNotFound(orphan);

        String unknownJob = UUID.randomUUID().toString();
        mvc.perform(post("/api/v1/jobs/{id}/shares", unknownJob))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("JOB_NOT_FOUND"))
                .andExpect(jsonPath("$.error.jobId").value(unknownJob));
    }

    @Test
    void revokeAllForJobEndsEveryActiveLinkOfThatJobOnly() throws Exception {
        String jobId = completedJob("many-dog.jpg");
        String otherJob = completedJob("other-dog.jpg");
        String revokedFirst = createShare(jobId);
        String active = createShare(jobId);
        String expired = saveLink(jobId, Instant.now().minus(Duration.ofDays(8)), Instant.now().minusSeconds(1));
        String otherJobsLink = createShare(otherJob);
        mvc.perform(delete("/api/v1/shares/{token}", revokedFirst)).andExpect(status().isNoContent());

        assertThat(shareService.revokeAllForJob(jobId)).as("only the one still active").isEqualTo(1);
        assertShareNotFound(active);
        assertShareNotFound(revokedFirst);
        assertShareNotFound(expired);
        mvc.perform(get("/api/v1/shares/{token}", otherJobsLink)).andExpect(status().isOk());
        assertThat(shareLinks.findById(active)).get().extracting(ShareLink::getRevokedAt).isNotNull();
    }

    @Test
    void sharePageForwardsToTheViewer() throws Exception {
        mvc.perform(get("/s/{token}", randomToken()))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/share.html"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));

        String html = mvc.perform(get("/share.html"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains("<model-viewer", "https://cdn.jsdelivr.net/npm/@google/model-viewer@3.5.0/dist/model-viewer.min.js",
                "ar-modes=\"webxr scene-viewer\"", "slot=\"ar-button\"", "내 방에 놓아 보기",
                "src=\"/share.js\"", "href=\"/share.css\"");
        mvc.perform(get("/share.js")).andExpect(status().isOk());
        mvc.perform(get("/share.css")).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ helpers

    private void assertShareNotFound(String token) throws Exception {
        List<RequestBuilder> requests = List.of(
                get("/api/v1/shares/{token}", token),
                get("/api/v1/shares/{token}/asset", token),
                delete("/api/v1/shares/{token}", token));
        for (RequestBuilder request : requests) {
            JsonNode error = json(mvc.perform(request)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("SHARE_NOT_FOUND"))
                    .andReturn()).path("error");
            assertThat(error.path("jobId").isNull() || error.path("jobId").isMissingNode())
                    .as("SHARE_NOT_FOUND never carries a job id").isTrue();
        }
    }

    private String createShare(String jobId) throws Exception {
        return json(mvc.perform(post("/api/v1/jobs/{id}/shares", jobId))
                .andExpect(status().isCreated()).andReturn()).path("token").asText();
    }

    /** Stores a link as it would look at another point in time (expired, about to expire, or of a deleted job). */
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

    private String completedJob(String filename) throws Exception {
        String jobId = upload(filename);
        awaitCompleted(jobId);
        return jobId;
    }

    private String upload(String filename) throws Exception {
        MockMultipartFile photo = new MockMultipartFile("photos", filename, "image/jpeg", new byte[4]);
        return json(mvc.perform(multipart("/api/v1/jobs").file(photo)).andExpect(status().isAccepted()).andReturn())
                .path("jobId").asText();
    }

    private void awaitCompleted(String jobId) {
        await().pollInterval(Duration.ofMillis(50)).atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(json(mvc.perform(get("/api/v1/jobs/{id}", jobId))
                        .andExpect(status().isOk()).andReturn()).path("status").asText()).isEqualTo("COMPLETED"));
    }

    private JsonNode json(MvcResult result) throws IOException {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }
}
