package com.example.mockbackend;

import com.example.mockbackend.domain.Job;
import com.example.mockbackend.preprocess.AnimalDetector.Detection;
import com.example.mockbackend.preprocess.Box;
import com.example.mockbackend.preprocess.PreprocessException;
import com.example.mockbackend.preprocess.YoloxCodec;
import com.example.mockbackend.repository.IdempotencyKeyRepository;
import com.example.mockbackend.repository.JobRepository;
import com.example.mockbackend.service.preprocess.PreprocessModels;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The first-photo check before the 202 (ERROR_CODES.md "대표 사진 검사"), with a fake detector: every refusal code with
 * the v1 envelope and nothing left behind, the photo let through (and recorded) when the check cannot run, the
 * measurements on the job, and what is not checked (other photos, replays, v0).
 */
@SpringBootTest(properties = {"preprocess.check.enabled=true", "preprocess.check.wait-ms=200",
        "mock.worker.pending-ms=50", "mock.worker.processing-ms=50"})
@AutoConfigureMockMvc
class JobV1PhotoCheckTest {
    private static final Box SUBJECT = new Box(200, 150, 600, 450); // in the 800 x 600 photos below
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    static Path storage;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("storage.upload.dir", () -> storage.resolve("uploads").toString());
        registry.add("storage.result.dir", () -> storage.resolve("results").toString());
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JobRepository jobs;

    @Autowired
    IdempotencyKeyRepository keys;

    @MockBean
    PreprocessModels models;

    private volatile List<Detection> found = List.of(dog(SUBJECT));
    private volatile PreprocessException failure;
    private volatile long delayMs;
    private volatile CountDownLatch hold;
    private final CountDownLatch entered = new CountDownLatch(1);

    @BeforeEach
    void fakeDetector() {
        when(models.checkEnabled()).thenReturn(true);
        when(models.detector()).thenReturn(image -> {
            if (failure != null) {
                throw failure;
            }
            try {
                if (delayMs > 0) {
                    Thread.sleep(delayMs);
                }
                CountDownLatch h = hold;
                if (h != null) {
                    entered.countDown();
                    h.await(5, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return found;
        });
    }

    @Test
    void aGoodPhotoIsAcceptedWithItsCheckRecordedApartFromTheUpload() throws Exception {
        delayMs = 300;

        Job job = job(upload(photo("dog.png", scene(800, 600, true, 1.0))).andExpect(status().isAccepted()));

        assertThat(job.getCheckResult()).isEqualTo("PASSED");
        assertThat(job.getCheckMs()).isGreaterThanOrEqualTo(300);
        assertThat(job.getUploadMs()).as("uploadMs leaves the check out").isNotNull().isLessThan(job.getCheckMs());
    }

    @Test
    void everyRefusalAnswers400WithItsCodeAndLeavesNothingBehind() throws Exception {
        long jobsBefore = jobs.count();
        long keysBefore = keys.count();
        Box small = new Box(300, 200, 400, 300);

        refused("UNREADABLE_IMAGE", photo("broken.jpg", "not a picture".getBytes(), "image/jpeg"));
        refused("IMAGE_TOO_SMALL", photo("tiny.png", png(sceneImage(300, 200, new Box(50, 50, 250, 150), true, 1.0))));
        found = List.of();
        refused("NO_ANIMAL_DETECTED", photo("room.png", scene(800, 600, true, 1.0)));
        found = List.of(dog(SUBJECT), new Detection(new Box(620, 300, 780, 450), YoloxCodec.COCO_CAT, 0.8f));
        refused("TOO_MANY_ANIMALS", photo("two.png", scene(800, 600, true, 1.0)));
        found = List.of(dog(small));
        refused("ANIMAL_TOO_SMALL", photo("far.png", png(sceneImage(800, 600, small, true, 1.0))));
        found = List.of(dog(SUBJECT));
        refused("IMAGE_TOO_DARK", photo("dark.png", scene(800, 600, true, 0.15)));
        refused("IMAGE_TOO_BLURRY", photo("blurry.png", scene(800, 600, false, 1.0)));

        assertThat(jobs.count()).as("no job for a refused photo").isEqualTo(jobsBefore);
        assertThat(keys.count()).as("no Idempotency-Key linked").isEqualTo(keysBefore);
        assertThat(storedFiles()).as("no photo kept").noneMatch(name -> name.contains("broken") || name.contains("tiny")
                || name.contains("room") || name.contains("two") || name.contains("far") || name.contains("dark")
                || name.contains("blurry"));
    }

    @Test
    void onlyTheFirstPhotoIsChecked() throws Exception {
        upload(photo("first.png", scene(800, 600, true, 1.0)), photo("second.jpg", "not a picture".getBytes(),
                "image/jpeg")).andExpect(status().isAccepted());
    }

    @Test
    void aReplayIsNotCheckedAgainAndARefusalLinksNoKey() throws Exception {
        String accepted = json(upload("key-photo-check-1", photo("dog.png", scene(800, 600, true, 1.0)))
                .andExpect(status().isAccepted())).path("jobId").asText();
        found = List.of(); // the same photo would now be refused
        String replay = json(upload("key-photo-check-1", photo("dog.png", scene(800, 600, true, 1.0)))
                .andExpect(status().isAccepted())).path("jobId").asText();
        assertThat(replay).isEqualTo(accepted);

        upload("key-photo-check-2", photo("room.png", scene(800, 600, true, 1.0)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("NO_ANIMAL_DETECTED"));
        found = List.of(dog(SUBJECT));
        upload("key-photo-check-2", photo("dog.png", scene(800, 600, true, 1.0))).andExpect(status().isAccepted());
    }

    @Test
    void whenTheCheckCannotRunThePhotoIsAcceptedAndRecorded() throws Exception {
        failure = new PreprocessException("session closed");

        Job job = job(upload(photo("dog.png", scene(800, 600, true, 1.0))).andExpect(status().isAccepted()));

        assertThat(job.getCheckResult()).isEqualTo("SKIPPED:ERROR");
        assertThat(job.getCheckMs()).isNotNull();
    }

    @Test
    void aBusyCheckLetsThePhotoThroughAfterTheWait() throws Exception {
        hold = new CountDownLatch(1);
        CompletableFuture<ResultActions> first = CompletableFuture.supplyAsync(() -> {
            try {
                return upload(photo("first.png", scene(800, 600, true, 1.0)));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(entered.await(5, TimeUnit.SECONDS)).as("the first check holds the only slot").isTrue();

        Job second = job(upload(photo("second.png", scene(800, 600, true, 1.0))).andExpect(status().isAccepted()));
        hold.countDown();

        assertThat(second.getCheckResult()).isEqualTo("SKIPPED:BUSY");
        assertThat(second.getCheckMs()).isGreaterThanOrEqualTo(200);
        first.get(5, TimeUnit.SECONDS).andExpect(status().isAccepted());
    }

    @Test
    void everyRefusalReasonIsAnApiErrorCodeOfStatus400() {
        for (com.example.mockbackend.preprocess.PhotoCheck.Reason reason
                : com.example.mockbackend.preprocess.PhotoCheck.Reason.values()) {
            assertThat(com.example.mockbackend.exception.ErrorCode.valueOf(reason.name()).httpStatus().value())
                    .as(reason.name()).isEqualTo(400);
        }
    }

    @Test
    void theFrozenV0EndpointIsNotChecked() throws Exception {
        found = List.of();
        mvc.perform(multipart("/api/jobs").file(photo("room.png", scene(800, 600, true, 1.0))))
                .andExpect(status().is2xxSuccessful());
    }

    private void refused(String code, MockMultipartFile file) throws Exception {
        upload(file).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(code))
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("photos[0]")));
    }

    private ResultActions upload(MockMultipartFile... files) throws Exception {
        return upload(null, files);
    }

    private ResultActions upload(String key, MockMultipartFile... files) throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart("/api/v1/jobs");
        for (MockMultipartFile file : files) {
            request.file(file);
        }
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mvc.perform(request);
    }

    private Job job(ResultActions accepted) throws Exception {
        String id = json(accepted).path("jobId").asText();
        return jobs.findById(id).orElseThrow();
    }

    private static com.fasterxml.jackson.databind.JsonNode json(ResultActions result) throws Exception {
        return JSON.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private static List<String> storedFiles() throws Exception {
        Path dir = storage.resolve("uploads");
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).toList();
        }
    }

    private static Detection dog(Box box) {
        return new Detection(box, YoloxCodec.COCO_DOG, 0.9f);
    }

    private static MockMultipartFile photo(String name, byte[] bytes) {
        return photo(name, bytes, name.endsWith(".png") ? "image/png" : "image/jpeg");
    }

    private static MockMultipartFile photo(String name, byte[] bytes, String type) {
        return new MockMultipartFile("photos", name, type, bytes);
    }

    private static byte[] scene(int width, int height, boolean sharp, double light) {
        return png(sceneImage(width, height, SUBJECT, sharp, light));
    }

    /** Light grey with a sharp checkerboard (or a smooth gradient) subject, every pixel times {@code light}. */
    private static BufferedImage sceneImage(int width, int height, Box subject, boolean sharp, double light) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                boolean in = x >= subject.x1() && x < subject.x2() && y >= subject.y1() && y < subject.y2();
                int v = !in ? 200 : sharp ? (((x / 3) + (y / 3)) % 2 == 0 ? 20 : 235)
                        : 60 + (int) (120.0 * (x - subject.x1()) / subject.width());
                int lit = (int) Math.min(255, Math.round(v * light));
                image.setRGB(x, y, new Color(lit, lit, lit).getRGB());
            }
        }
        return image;
    }

    private static byte[] png(BufferedImage image) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
