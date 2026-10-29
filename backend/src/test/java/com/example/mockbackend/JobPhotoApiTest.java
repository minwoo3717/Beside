package com.example.mockbackend;

import com.example.mockbackend.domain.Job;
import com.example.mockbackend.domain.StoredUpload;
import com.example.mockbackend.repository.JobRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Uploaded photos for the job detail page (GET /api/v1/jobs/{jobId}/photos/{index}, tag quality) and the page itself.
 * Photos are served whatever the job status, typed by the stored file name, and never reveal where they are stored.
 */
@SpringBootTest
@AutoConfigureMockMvc
class JobPhotoApiTest {
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 9};

    @TempDir
    static Path storage;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("storage.upload.dir", () -> storage.resolve("uploads").toString());
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JobRepository jobs;

    @Test
    void servesEachUploadedPhotoByIndexWithItsImageType() throws Exception {
        String jobId = upload(new MockMultipartFile("photos", "front dog.jpg", "image/jpeg", JPEG),
                new MockMultipartFile("photos", "side.PNG", "image/png", PNG));

        MvcResult first = mvc.perform(get("/api/v1/jobs/{id}/photos/0", jobId))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg"))
                .andExpect(content().bytes(JPEG))
                .andExpect(header().longValue("Content-Length", JPEG.length))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", "private, max-age=3600"))
                .andReturn();
        assertNoServerPath(first, jobId);

        mvc.perform(get("/api/v1/jobs/{id}/photos/1", jobId))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(content().bytes(PNG));
    }

    @Test
    void indexesOutsideTheUploadsAreNotFoundAndUnknownJobsJobNotFound() throws Exception {
        String jobId = upload(new MockMultipartFile("photos", "dog.jpg", "image/jpeg", JPEG));
        for (String index : List.of("1", "2", "-1", "999999")) {
            MvcResult missing = mvc.perform(get("/api/v1/jobs/{id}/photos/{index}", jobId, index))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
                    .andReturn();
            assertNoServerPath(missing, jobId);
        }
        mvc.perform(get("/api/v1/jobs/{id}/photos/abc", jobId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        String unknown = UUID.randomUUID().toString();
        mvc.perform(get("/api/v1/jobs/{id}/photos/0", unknown))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("JOB_NOT_FOUND"))
                .andExpect(jsonPath("$.error.jobId").value(unknown));
    }

    @Test
    void anythingButAKnownImageExtensionIsServedAsOctetStream() throws Exception {
        // The declared type passes the upload check outside prod; the stored name decides how it is served.
        String jobId = upload(new MockMultipartFile("photos", "page.html", "image/jpeg", "<script>x</script>".getBytes(StandardCharsets.UTF_8)),
                new MockMultipartFile("photos", "noext", "image/webp", JPEG),
                new MockMultipartFile("photos", "pet.webp", "image/webp", JPEG));
        for (int index = 0; index < 2; index++) {
            mvc.perform(get("/api/v1/jobs/{id}/photos/{index}", jobId, index))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType("application/octet-stream"))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        }
        mvc.perform(get("/api/v1/jobs/{id}/photos/2", jobId))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/webp"));
    }

    @Test
    void aPhotoGoneFromTheDiskIsNotFound() throws Exception {
        String jobId = upload(new MockMultipartFile("photos", "gone.jpg", "image/jpeg", JPEG));
        Job job = jobs.findById(jobId).orElseThrow();
        Files.delete(Path.of(job.getUploads().get(0).path()));
        MvcResult missing = mvc.perform(get("/api/v1/jobs/{id}/photos/0", jobId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
                .andReturn();
        assertNoServerPath(missing, jobId);
    }

    @Test
    void jobDetailPageIsServedAtJobWithAndWithoutTrailingSlash() throws Exception {
        mvc.perform(get("/job")).andExpect(status().isOk()).andExpect(forwardedUrl("/job/index.html"));
        mvc.perform(get("/job/")).andExpect(status().isOk()).andExpect(forwardedUrl("/job/index.html"));
        String html = mvc.perform(get("/job/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/job/job.js")))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        // Same pinned model-viewer build (and SRI hash) as the share page.
        String share = mvc.perform(get("/share.html")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String modelViewer = share.lines().filter(line -> line.contains("model-viewer@")).findFirst().orElseThrow().strip();
        assertThat(html).contains(modelViewer);
        for (String asset : List.of("/job/job.js", "/job/job-format.js", "/job/qr.js", "/job/job.css")) {
            mvc.perform(get(asset)).andExpect(status().isOk());
        }
        // The dashboard links here and carries the report panels' helpers.
        mvc.perform(get("/admin/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/admin/admin-measurement.js")));
        mvc.perform(get("/admin/admin-measurement.js")).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ helpers

    private String upload(MockMultipartFile... photos) throws Exception {
        var request = multipart("/api/v1/jobs");
        for (MockMultipartFile photo : photos) {
            request.file(photo);
        }
        return objectMapper.readTree(mvc.perform(request).andExpect(status().isAccepted()).andReturn()
                .getResponse().getContentAsByteArray()).path("jobId").asText();
    }

    /** Neither the storage folder nor the stored file name ({jobId}_{name}) may leak into headers or the body. */
    private void assertNoServerPath(MvcResult result, String jobId) throws Exception {
        StringBuilder exposed = new StringBuilder();
        Collection<String> names = result.getResponse().getHeaderNames();
        for (String name : names) {
            exposed.append(name).append(": ").append(result.getResponse().getHeaders(name)).append('\n');
        }
        String contentType = result.getResponse().getContentType();
        if (contentType == null || contentType.startsWith("application/json")) {
            exposed.append(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
            JsonNode error = objectMapper.readTree(result.getResponse().getContentAsByteArray()).path("error");
            assertThat(error.path("message").asText()).isNotEmpty();
        }
        assertThat(exposed.toString())
                .doesNotContain(storage.getFileName().toString())
                .doesNotContain("uploads")
                .doesNotContain(jobId + "_");
        Job job = jobs.findById(jobId).orElseThrow();
        for (StoredUpload stored : job.getUploads()) {
            assertThat(exposed.toString()).doesNotContain(stored.path());
        }
    }
}
