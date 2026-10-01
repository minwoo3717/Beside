package com.example.mockbackend;

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
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.hamcrest.Matchers.containsString;

@SpringBootTest
@AutoConfigureMockMvc
class JobAsyncIntegrationTest {
    @TempDir
    static Path storage;

    @DynamicPropertySource
    static void storageProperties(DynamicPropertyRegistry registry) {
        registry.add("storage.upload.dir", () -> storage.resolve("uploads").toString());
        registry.add("storage.result.sample", () -> storage.resolve("sample-dog.glb").toString());
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void acceptsUploadBeforeWorkerCompletesAndPreservesApiContract() throws Exception {
        byte[] sample = "mock download fixture".getBytes(StandardCharsets.UTF_8);
        Files.write(storage.resolve("sample-dog.glb"), sample);
        MockMultipartFile first = new MockMultipartFile("photos", "dog-1.jpg", "image/jpeg",
                new byte[]{1, 2, 3});
        MockMultipartFile second = new MockMultipartFile("photos", "dog-2.jpg", "image/jpeg",
                new byte[]{4, 5, 6});

        long started = System.nanoTime();
        MvcResult upload = mvc.perform(multipart("/api/jobs").file(first).file(second))
                .andReturn();
        Duration responseTime = Duration.ofNanos(System.nanoTime() - started);

        assertThat(upload.getResponse().getStatus()).isEqualTo(202);
        assertThat(upload.getResponse().getContentAsByteArray()).isEmpty();
        assertThat(responseTime).isLessThan(Duration.ofSeconds(2));
        String location = upload.getResponse().getHeader("Location");
        assertThat(location).matches("/api/jobs/[0-9a-f-]{36}");
        String jobId = location.substring("/api/jobs/".length());

        JsonNode pending = readJob(location);
        assertThat(pending.path("id").asText()).isEqualTo(jobId);
        assertThat(pending.path("status").asText()).isEqualTo("PENDING");
        assertThat(pending.path("resultPath").isNull()).isTrue();
        assertThat(pending.path("uploadedFiles").size()).isEqualTo(2);
        assertThat(Files.readAllBytes(storage.resolve("uploads").resolve(jobId + "_dog-1.jpg")))
                .containsExactly(first.getBytes());
        assertThat(Files.readAllBytes(storage.resolve("uploads").resolve(jobId + "_dog-2.jpg")))
                .containsExactly(second.getBytes());
        mvc.perform(get(location + "/result")).andExpect(status().isBadRequest());

        await().pollInterval(Duration.ofMillis(100)).atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(readJob(location).path("status").asText())
                        .isEqualTo("PROCESSING"));
        await().pollInterval(Duration.ofMillis(100)).atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> {
                    JsonNode completed = readJob(location);
                    assertThat(completed.path("status").asText()).isEqualTo("COMPLETED");
                    assertThat(completed.path("resultPath").asText())
                            .isEqualTo(storage.resolve("sample-dog.glb").toString());
                    assertThat(completed.path("durationMs").asLong()).isGreaterThanOrEqualTo(5000);
                });

        mvc.perform(get(location + "/result"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("model/gltf-binary"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=sample-dog.glb"))
                .andExpect(content().bytes(sample));
    }

    @Test
    void servesMockHomepageAndDistinguishesClientErrors() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk()).andExpect(forwardedUrl("index.html"));
        mvc.perform(get("/index.html")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Beside Again")));
        mvc.perform(get("/styles.css")).andExpect(status().isOk());
        mvc.perform(get("/app.js")).andExpect(status().isOk());
        mvc.perform(get("/missing-page")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Resource not found"));
        mvc.perform(get("/favicon.ico")).andExpect(status().isNotFound());
        mvc.perform(get("/api/jobs")).andExpect(status().isMethodNotAllowed());
        mvc.perform(multipart("/api/jobs")).andExpect(status().isBadRequest());
    }

    private JsonNode readJob(String location) throws Exception {
        MvcResult result = mvc.perform(get(location)).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }
}
