package com.example.mockbackend;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Profile "prod" laid over "mock" (a public server runs real,prod; prod comes last so it wins):
 * the H2 console and the API docs are off, and v1 uploads must really be JPEG, PNG or WEBP files.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"mock", "prod"})
class ProdProfileTest {

    @TempDir
    static Path storage;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("storage.upload.dir", () -> storage.resolve("uploads").toString());
        registry.add("storage.result.dir", () -> storage.resolve("results").toString());
        registry.add("storage.result.sample", () -> storage.resolve("sample-dog.glb").toString());
        registry.add("mock.worker.pending-ms", () -> "50");
        registry.add("mock.worker.processing-ms", () -> "50");
    }

    @Autowired
    MockMvc mvc;

    @Test
    void consoleAndApiDocsAreClosed() throws Exception {
        mvc.perform(get("/h2-console")).andExpect(status().isNotFound());
        mvc.perform(get("/h2-console/login.jsp")).andExpect(status().isNotFound());
        mvc.perform(get("/v3/api-docs")).andExpect(status().isNotFound());
        mvc.perform(get("/swagger-ui.html")).andExpect(status().isNotFound());
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/healthz")).andExpect(status().isOk())
                .andExpect(jsonPath("$.profile").value("mock,prod"));
    }

    @Test
    void resultDirectoryComesFromSettings() {
        assertThat(Files.isDirectory(storage.resolve("results"))).isTrue();
    }

    @Test
    void photosWhoseBytesAreNoImageAreRejectedBeforeTheQueue() throws Exception {
        upload(photo("zeros.jpg", "image/jpeg", new byte[64]))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_IMAGE_TYPE"));
        upload(photo("note.png", "image/png", "not an image at all".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_IMAGE_TYPE"));
        // One bad photo among good ones rejects the whole request.
        upload(photo("dog.jpg", "image/jpeg", jpegBytes()), photo("tiny.webp", "image/webp", new byte[]{'R', 'I', 'F', 'F'}))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_IMAGE_TYPE"));
    }

    @Test
    void realImageHeadersAreAccepted() throws Exception {
        upload(photo("dog.jpg", "image/jpeg", jpegBytes())).andExpect(status().isAccepted());
        upload(photo("dog.png", "image/png", pngBytes())).andExpect(status().isAccepted());
        upload(photo("dog.webp", "image/webp", webpBytes())).andExpect(status().isAccepted());
        // A mislabelled but real image is still an image the model can read.
        upload(photo("dog.jpg", "image/jpeg", pngBytes())).andExpect(status().isAccepted());
    }

    // ------------------------------------------------------------------ helpers

    private org.springframework.test.web.servlet.ResultActions upload(MockMultipartFile... files) throws Exception {
        var request = multipart("/api/v1/jobs");
        for (MockMultipartFile file : files) {
            request = request.file(file);
        }
        return mvc.perform(request);
    }

    private static MockMultipartFile photo(String name, String type, byte[] bytes) {
        return new MockMultipartFile("photos", name, type, bytes);
    }

    private static byte[] jpegBytes() {
        return padded(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0});
    }

    private static byte[] pngBytes() {
        return padded(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
    }

    private static byte[] webpBytes() {
        return padded(new byte[]{'R', 'I', 'F', 'F', 0x24, 0, 0, 0, 'W', 'E', 'B', 'P'});
    }

    private static byte[] padded(byte[] header) {
        return Arrays.copyOf(header, 64);
    }
}
