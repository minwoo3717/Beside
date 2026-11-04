package com.example.mockbackend;

import com.example.mockbackend.domain.JobRun;
import com.example.mockbackend.domain.JobStatus;
import com.example.mockbackend.domain.RunDetails;
import com.example.mockbackend.preprocess.AnimalDetector.Detection;
import com.example.mockbackend.preprocess.Box;
import com.example.mockbackend.preprocess.ForegroundSegmenter;
import com.example.mockbackend.preprocess.YoloxCodec;
import com.example.mockbackend.repository.JobRunRepository;
import com.example.mockbackend.service.preprocess.PreparedPhoto;
import com.example.mockbackend.service.preprocess.PreprocessModels;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The cutout of the real worker (after the 202, before the inference slot) against a fake inference service and fake
 * models: the square PNG goes to /infer as the first photo, a retry reuses it, a failure falls back to the original,
 * and every run records preprocessMs / preprocessResult / preprocessVersion (docs/METRICS.md §1).
 */
@SpringBootTest(properties = "preprocess.cutout.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("real")
class RealJobWorkerPreprocessTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Color FUR = new Color(200, 40, 40);
    private static final Box ANIMAL = new Box(300, 200, 500, 350);

    @TempDir
    static Path storage;

    private static HttpServer inference;
    private static final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private static volatile int answerStatus;
    private static volatile String answerBody;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("inference.base-url", () -> "http://127.0.0.1:" + inference.getAddress().getPort());
        registry.add("storage.upload.dir", () -> storage.resolve("uploads").toString());
        registry.add("storage.result.dir", () -> storage.resolve("results").toString());
        registry.add("storage.preprocessed.dir", () -> storage.resolve("preprocessed").toString());
    }

    @BeforeAll
    static void startFakeInferenceService() throws IOException {
        inference = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        inference.createContext("/infer", exchange -> {
            try {
                requests.add(JSON.readTree(exchange.getRequestBody()));
                byte[] bytes = answerBody.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(answerStatus, bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally {
                exchange.close();
            }
        });
        inference.start();
    }

    @AfterAll
    static void stop() {
        inference.stop(0);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JobRunRepository jobRuns;

    @MockBean
    PreprocessModels models;

    private volatile List<Detection> found = List.of(new Detection(ANIMAL, YoloxCodec.COCO_DOG, 0.9f));

    /** Alpha 1 where the pixel is the fur colour. */
    private static final ForegroundSegmenter RED_IS_FOREGROUND = new ForegroundSegmenter() {
        @Override
        public float[] segment(BufferedImage crop) {
            float[] alpha = new float[crop.getWidth() * crop.getHeight()];
            for (int y = 0; y < crop.getHeight(); y++) {
                for (int x = 0; x < crop.getWidth(); x++) {
                    Color c = new Color(crop.getRGB(x, y));
                    alpha[y * crop.getWidth() + x] = c.getRed() > 180 && c.getGreen() < 80 ? 1f : 0f;
                }
            }
            return alpha;
        }

        @Override
        public String name() {
            return "fake-seg#00000000";
        }
    };

    @BeforeEach
    void fakes() throws IOException {
        when(models.checkEnabled()).thenReturn(false);
        when(models.cutoutEnabled()).thenReturn(true);
        when(models.detector()).thenReturn(image -> found);
        when(models.segmenter()).thenReturn(RED_IS_FOREGROUND);
        answerOk();
    }

    @Test
    void theSquareCutoutGoesToInferAsTheFirstPhoto() throws Exception {
        String jobId = create(photo("dog.png", true), photo("second.png", false));
        JobRun run = awaitRun(jobId, 1);

        assertThat(run.getStatus()).isEqualTo(JobStatus.COMPLETED);
        assertThat(run.getPreprocessResult()).isEqualTo("APPLIED");
        assertThat(run.getPreprocessMs()).isNotNull().isNotNegative();
        assertThat(run.getPreprocessVersion()).startsWith("cutout1 bg=remove").contains("fake-seg#00000000");

        JsonNode sent = requestOf(jobId);
        assertThat(sent.path("imagePaths")).hasSize(2);
        Path first = Path.of(sent.path("imagePaths").get(0).asText());
        assertThat(first.getParent()).isEqualTo(storage.resolve("preprocessed").toAbsolutePath().normalize());
        assertThat(first.getFileName().toString()).startsWith(jobId + "-").endsWith(".png");
        BufferedImage square = ImageIO.read(first.toFile());
        assertThat(square.getWidth()).isEqualTo(square.getHeight());
        assertThat(square.getColorModel().hasAlpha()).isTrue();
        assertThat((square.getRGB(0, 0) >>> 24) & 0xFF).as("transparent corner").isZero();
        assertThat(Path.of(sent.path("imagePaths").get(1).asText()).getFileName().toString())
                .as("the other photos go as uploaded").endsWith("second.png");
    }

    @Test
    void aRetryReusesTheCutout() throws Exception {
        answer(400, "{\"detail\":{\"code\":\"INFERENCE_FAILED\",\"message\":\"no\"}}");
        String jobId = create(photo("dog.png", true));
        JobRun failed = awaitRun(jobId, 1);
        assertThat(failed.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(failed.getPreprocessResult()).as("a failed run keeps its preprocessing facts").isEqualTo("APPLIED");
        String firstPhoto = requestOf(jobId).path("imagePaths").get(0).asText();

        answerOk();
        mvc.perform(post("/api/v1/jobs/{id}/retry", jobId)).andExpect(status().isAccepted());
        JobRun retried = awaitRun(jobId, 2);

        assertThat(retried.getStatus()).isEqualTo(JobStatus.COMPLETED);
        assertThat(retried.getPreprocessResult()).isEqualTo("REUSED");
        assertThat(retried.getPreprocessVersion()).isEqualTo(failed.getPreprocessVersion());
        assertThat(requestOf(jobId).path("imagePaths").get(0).asText()).isEqualTo(firstPhoto);
    }

    @Test
    void whenTheCutoutFailsTheOriginalGoes() throws Exception {
        found = List.of(); // nothing to cut out
        String jobId = create(photo("dog.png", true));
        JobRun run = awaitRun(jobId, 1);

        assertThat(run.getStatus()).isEqualTo(JobStatus.COMPLETED);
        assertThat(run.getPreprocessResult()).isEqualTo("FALLBACK:NO_ANIMAL");
        assertThat(Path.of(requestOf(jobId).path("imagePaths").get(0).asText()).getFileName().toString())
                .endsWith("dog.png");
    }

    @Test
    void offSendsTheOriginalAndSaysSo() throws Exception {
        when(models.cutoutEnabled()).thenReturn(false);
        String jobId = create(photo("dog.png", true));
        JobRun run = awaitRun(jobId, 1);

        assertThat(run.getPreprocessResult()).isEqualTo("OFF");
        assertThat(run.getPreprocessVersion()).isNull();
        assertThat(run.getPreprocessMs()).isNull();
        assertThat(Path.of(requestOf(jobId).path("imagePaths").get(0).asText()).getFileName().toString())
                .endsWith("dog.png");
    }

    @Test
    void thePreparedPhotoReplacesOnlyTheFirstPathAndName() {
        PreparedPhoto applied = new PreparedPhoto(Path.of("pre", "j-1.png"), "dog-cutout.png", 5L, "APPLIED", "v");

        assertThat(applied.paths(List.of(Path.of("u", "j_dog.jpg"), Path.of("u", "j_cat.jpg"))))
                .containsExactly(Path.of("pre", "j-1.png"), Path.of("u", "j_cat.jpg"));
        // upload mode names the part: the .png name makes InferenceClient send image/png
        assertThat(applied.names(List.of("dog.jpg", "cat.jpg"))).containsExactly("dog-cutout.png", "cat.jpg");
        assertThat(PreparedPhoto.cutoutName("우리 강아지.JPEG")).isEqualTo("우리 강아지-cutout.png");
        assertThat(PreparedPhoto.cutoutName("noext")).isEqualTo("noext-cutout.png");

        PreparedPhoto off = PreparedPhoto.off();
        assertThat(off.paths(List.of(Path.of("u", "a.jpg")))).containsExactly(Path.of("u", "a.jpg"));
        RunDetails details = off.apply(RunDetails.NONE);
        assertThat(details.preprocessResult()).isEqualTo("OFF");
        assertThat(applied.apply(RunDetails.NONE).preprocessMs()).isEqualTo(5L);
    }

    private String create(MockMultipartFile... photos) throws Exception {
        var request = multipart("/api/v1/jobs");
        for (MockMultipartFile photo : photos) {
            request.file(photo);
        }
        String body = mvc.perform(request).andExpect(status().isAccepted()).andReturn().getResponse()
                .getContentAsString();
        return JSON.readTree(body).path("jobId").asText();
    }

    private JobRun awaitRun(String jobId, int attempt) {
        String id = jobId + ":" + attempt;
        await().pollInterval(Duration.ofMillis(50)).atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(jobRuns.findById(id)).isPresent());
        return jobRuns.findById(id).orElseThrow();
    }

    private static JsonNode requestOf(String jobId) {
        return requests.stream().filter(r -> r.path("jobId").asText().equals(jobId))
                .reduce((first, second) -> second).orElseThrow();
    }

    /** A grey PNG, with the red animal rectangle when {@code animal}. */
    private static MockMultipartFile photo(String name, boolean animal) {
        BufferedImage image = new BufferedImage(800, 600, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(150, 150, 150));
        g.fillRect(0, 0, 800, 600);
        if (animal) {
            g.setColor(FUR);
            g.fillRect((int) ANIMAL.x1(), (int) ANIMAL.y1(), (int) ANIMAL.width(), (int) ANIMAL.height());
        }
        g.dispose();
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return new MockMultipartFile("photos", name, "image/png", out.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void answerOk() throws IOException {
        Path glb = storage.resolve("answer.glb");
        Files.write(glb, RealJobWorkerTest.minimalGlb());
        ObjectNode body = JSON.createObjectNode();
        body.put("glbPath", glb.toAbsolutePath().toString());
        body.putObject("metrics").put("inferMs", 7);
        body.put("passthrough", false);
        body.put("modelVersion", "fake-animallift-0");
        answer(200, body.toString());
    }

    private static void answer(int status, String body) {
        answerStatus = status;
        answerBody = body;
    }
}
