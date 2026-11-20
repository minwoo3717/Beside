package com.example.mockbackend;

import com.example.mockbackend.domain.Job;
import com.example.mockbackend.domain.JobStatus;
import com.example.mockbackend.preprocess.AnimalDetector.Detection;
import com.example.mockbackend.preprocess.Box;
import com.example.mockbackend.preprocess.YoloxCodec;
import com.example.mockbackend.repository.IdempotencyKeyRepository;
import com.example.mockbackend.repository.JobRepository;
import com.example.mockbackend.service.preprocess.PreprocessModels;
import com.example.mockbackend.service.preprocess.UploadCheck;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Queue cap (REVIEW_CHECKLIST #21, proposal before the contract meeting) with the first-photo check on, as in real and
 * prod: a full queue answers 503 before the photo is checked (D6 proposal: the cap before the check, so a refused
 * request costs no check slot and no ONNX run), and a photo the check refuses gives its place back. Cap 1, fake
 * detector (the JobV1PhotoCheckTest pattern); its own class because @MockBean and @SpyBean change the whole context.
 */
@SpringBootTest(properties = {"preprocess.check.enabled=true", "preprocess.check.wait-ms=200",
        "mock.worker.pending-ms=50", "mock.worker.processing-ms=50",
        "admission.max-pending=1", "admission.retry-after-seconds=45"})
@AutoConfigureMockMvc
class QueueAdmissionPhotoCheckTest {
    private static final Box SUBJECT = new Box(200, 150, 600, 450); // in the 800 x 600 photo below

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
    JobRepository jobRepository;

    @Autowired
    IdempotencyKeyRepository idempotencyKeys;

    @MockBean
    PreprocessModels models;

    @SpyBean
    UploadCheck uploadCheck;

    private volatile List<Detection> found = List.of(new Detection(SUBJECT, YoloxCodec.COCO_DOG, 0.9f));

    @BeforeEach
    void fakeDetector() {
        when(models.checkEnabled()).thenReturn(true);
        when(models.detector()).thenReturn(image -> found);
    }

    @AfterEach
    void clear() {
        idempotencyKeys.deleteAll();
        jobRepository.deleteAll();
    }

    @Test
    void aFullQueueAnswers503BeforeThePhotoIsChecked() throws Exception {
        jobRepository.save(pendingJob());

        mvc.perform(multipart("/api/v1/jobs").file(dogPhoto()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "45"))
                .andExpect(jsonPath("$.error.code").value("QUEUE_FULL"));
        found = List.of(); // a photo the check would refuse: still 503 first
        mvc.perform(multipart("/api/v1/jobs").file(dogPhoto()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("QUEUE_FULL"));

        verify(uploadCheck, never()).check(any());
        assertThat(jobRepository.count()).isEqualTo(1);
    }

    @Test
    void aPhotoTheCheckRefusesGivesItsPlaceBack() throws Exception {
        found = List.of(); // no dog or cat
        mvc.perform(multipart("/api/v1/jobs").file(dogPhoto()))
                .andExpect(status().isBadRequest())
                .andExpect(header().doesNotExist("Retry-After"))
                .andExpect(jsonPath("$.error.code").value("NO_ANIMAL_DETECTED"));

        // Cap 1: had the refused request kept its place, this one would get 503.
        found = List.of(new Detection(SUBJECT, YoloxCodec.COCO_DOG, 0.9f));
        String body = mvc.perform(multipart("/api/v1/jobs").file(dogPhoto()))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        verify(uploadCheck, times(2)).check(any());

        String jobId = body.replaceAll(".*\"jobId\"\\s*:\\s*\"([^\"]+)\".*", "$1");
        long deadline = System.currentTimeMillis() + 10_000; // the mock worker saves the job again: let it end first
        while (jobRepository.findById(jobId).map(Job::getStatus).orElse(null) != JobStatus.COMPLETED) {
            assertThat(System.currentTimeMillis()).as("job %s did not finish", jobId).isLessThan(deadline);
            Thread.sleep(20);
        }
    }

    private static Job pendingJob() {
        Instant now = Instant.now();
        Job job = new Job();
        job.setId(UUID.randomUUID().toString());
        job.setStatus(JobStatus.PENDING);
        job.setCreatedAt(now);
        job.setUpdatedAt(now);
        job.setQueuedAt(now);
        job.setProgress(0.0);
        job.setAttempt(1);
        return job;
    }

    /** 800 x 600, bright, with a sharp checkered subject where the fake detector puts the dog. */
    private static MockMultipartFile dogPhoto() throws IOException {
        BufferedImage image = new BufferedImage(800, 600, BufferedImage.TYPE_3BYTE_BGR);
        for (int y = 0; y < 600; y++) {
            for (int x = 0; x < 800; x++) {
                boolean in = x >= SUBJECT.x1() && x < SUBJECT.x2() && y >= SUBJECT.y1() && y < SUBJECT.y2();
                int v = !in ? 200 : ((x / 3) + (y / 3)) % 2 == 0 ? 20 : 235;
                image.setRGB(x, y, new Color(v, v, v).getRGB());
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return new MockMultipartFile("photos", "dog.png", "image/png", out.toByteArray());
    }
}
