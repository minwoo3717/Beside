package com.example.mockbackend;

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

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Thumbnails (GET /api/v1/jobs/{id}/thumbnail, JobResponse.thumbnailUrl): made from the first photo when the job is
 * accepted, as a JPEG whose longer side is 256 px. Real images are drawn by TestImages (left half red, right half blue)
 * so the test can check size, orientation and the white background behind transparency. A photo the server cannot read
 * still makes a job — only without a thumbnail.
 */
@SpringBootTest
@AutoConfigureMockMvc
class JobThumbnailTest {
    @TempDir
    static Path storage;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("storage.upload.dir", () -> storage.resolve("uploads").toString());
        registry.add("storage.thumb.dir", () -> storage.resolve("thumbs").toString());
        registry.add("storage.result.sample", () -> storage.resolve("sample-dog.glb").toString());
        registry.add("mock.worker.pending-ms", () -> "200");
        registry.add("mock.worker.processing-ms", () -> "100");
    }

    @BeforeAll
    static void writeSample() throws IOException {
        Files.write(storage.resolve("sample-dog.glb"), "glTF thumb sample".getBytes(StandardCharsets.UTF_8));
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void jpegUploadGetsA256PixelJpegThumbnailOfTheFirstPhoto() throws Exception {
        MvcResult created = mvc.perform(multipart("/api/v1/jobs")
                        .file(new MockMultipartFile("photos", "wide.jpg", "image/jpeg", TestImages.jpeg(600, 400)))
                        .file(new MockMultipartFile("photos", "second.png", "image/png", TestImages.pngWithAlpha(50, 900))))
                .andExpect(status().isAccepted())
                .andReturn();
        String jobId = json(created).path("jobId").asText();

        JsonNode job = readJob(jobId);
        assertThat(job.path("thumbnailUrl").asText()).isEqualTo("/api/v1/jobs/" + jobId + "/thumbnail");
        assertThat(Files.isRegularFile(storage.resolve("thumbs").resolve(jobId + ".jpg"))).as("storage.thumb.dir/{jobId}.jpg").isTrue();

        BufferedImage thumb = thumbnail(job.path("thumbnailUrl").asText());
        assertThat(thumb.getWidth()).as("longer side 256").isEqualTo(256);
        assertThat(thumb.getHeight()).as("600x400 keeps its ratio").isEqualTo(171);
        assertThat(TestImages.near(thumb, 20, 85, TestImages.LEFT)).as("first photo, left half red").isTrue();
        assertThat(TestImages.near(thumb, 236, 85, TestImages.RIGHT)).isTrue();
    }

    @Test
    void pngWithTransparencyBecomesAJpegOnWhite() throws Exception {
        String jobId = upload("tall.png", "image/png", TestImages.pngWithAlpha(100, 300));
        BufferedImage thumb = thumbnail(readJob(jobId).path("thumbnailUrl").asText());
        assertThat(thumb.getWidth()).isEqualTo(85);
        assertThat(thumb.getHeight()).isEqualTo(256);
        assertThat(TestImages.near(thumb, 10, 128, TestImages.LEFT)).isTrue();
        assertThat(TestImages.isWhite(thumb, 75, 128)).as("transparent half on white").isTrue();
    }

    @Test
    void smallPhotosAreNotEnlarged() throws Exception {
        String jobId = upload("small.png", "image/png", TestImages.pngWithAlpha(64, 32));
        BufferedImage thumb = thumbnail(readJob(jobId).path("thumbnailUrl").asText());
        assertThat(thumb.getWidth()).isEqualTo(64);
        assertThat(thumb.getHeight()).isEqualTo(32);
    }

    @Test
    void exifOrientationIsApplied() throws Exception {
        // Orientation 6 = the camera was turned: show the wide 80x40 picture rotated 90 degrees clockwise (40x80),
        // so its left (red) half ends on top.
        String jobId = upload("phone.jpg", "image/jpeg", TestImages.jpegWithOrientation(80, 40, 6));
        BufferedImage thumb = thumbnail(readJob(jobId).path("thumbnailUrl").asText());
        assertThat(thumb.getWidth()).isEqualTo(40);
        assertThat(thumb.getHeight()).isEqualTo(80);
        assertThat(TestImages.near(thumb, 20, 10, TestImages.LEFT)).as("red on top").isTrue();
        assertThat(TestImages.near(thumb, 20, 70, TestImages.RIGHT)).as("blue at the bottom").isTrue();

        // 8 = turned the other way: red at the bottom.
        String other = upload("phone8.jpg", "image/jpeg", TestImages.jpegWithOrientation(80, 40, 8));
        BufferedImage turned = thumbnail(readJob(other).path("thumbnailUrl").asText());
        assertThat(turned.getWidth()).isEqualTo(40);
        assertThat(TestImages.near(turned, 20, 70, TestImages.LEFT)).as("red at the bottom").isTrue();
    }

    @Test
    void webpIsReadToo() throws Exception {
        String jobId = upload("dog.webp", "image/webp", TestImages.webp());
        JsonNode job = readJob(jobId);
        assertThat(job.path("thumbnailUrl").isTextual()).as("WEBP decoded (TwelveMonkeys imageio-webp)").isTrue();
        BufferedImage thumb = thumbnail(job.path("thumbnailUrl").asText());
        assertThat(thumb.getWidth()).isEqualTo(256);
        assertThat(thumb.getHeight()).isEqualTo(171);
        assertThat(TestImages.near(thumb, 20, 85, TestImages.LEFT)).isTrue();
        assertThat(TestImages.near(thumb, 236, 85, TestImages.RIGHT)).isTrue();
    }

    @Test
    void unreadablePhotosStillMakeAJobWithoutThumbnail() throws Exception {
        // Placeholder bytes like the older tests upload: the job is accepted, only the thumbnail is missing.
        String jobId = upload("zeros.jpg", "image/jpeg", new byte[16]);
        JsonNode job = readJob(jobId);
        assertThat(job.has("thumbnailUrl")).isTrue();
        assertThat(job.path("thumbnailUrl").isNull()).isTrue();
        assertThat(job.has("petId")).isTrue();
        assertThat(job.path("petId").isNull()).as("uploaded without a pet = unassigned").isTrue();

        mvc.perform(get("/api/v1/jobs/{id}/thumbnail", jobId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ASSET_NOT_FOUND"))
                .andExpect(jsonPath("$.error.jobId").value(jobId));

        String unknown = UUID.randomUUID().toString();
        mvc.perform(get("/api/v1/jobs/{id}/thumbnail", unknown))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("JOB_NOT_FOUND"));
    }

    // ------------------------------------------------------------------ helpers

    private BufferedImage thumbnail(String url) throws Exception {
        byte[] bytes = mvc.perform(get(url))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg"))
                .andExpect(header().string("Cache-Control", "private, max-age=86400"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(TestImages.isJpeg(bytes)).isTrue();
        BufferedImage image = TestImages.read(bytes);
        assertThat(image).isNotNull();
        return image;
    }

    private String upload(String filename, String type, byte[] bytes) throws Exception {
        return json(mvc.perform(multipart("/api/v1/jobs").file(new MockMultipartFile("photos", filename, type, bytes)))
                .andExpect(status().isAccepted()).andReturn()).path("jobId").asText();
    }

    private JsonNode readJob(String jobId) throws Exception {
        return json(mvc.perform(get("/api/v1/jobs/{id}", jobId)).andExpect(status().isOk()).andReturn());
    }

    private JsonNode json(MvcResult result) throws IOException {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }
}
