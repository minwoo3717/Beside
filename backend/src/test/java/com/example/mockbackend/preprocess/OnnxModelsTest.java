package com.example.mockbackend.preprocess;

import com.example.mockbackend.preprocess.AnimalDetector.Detection;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The real models (backend/models/README.md), run locally only: skipped unless the model folder holds them
 * (-Dbeside.models=<dir> or PREPROCESS_MODELSDIR; CI has neither). Photo-based checks also need the team's test
 * photos in src/test/resources/photos/ and are skipped without them.
 */
@Tag("models")
class OnnxModelsTest {
    static final Path PHOTOS = Path.of("src/test/resources/photos");
    private static YoloxDetector yolox;
    private static OnnxSaliencySegmenter u2net;

    static Path modelsDir() {
        String dir = System.getProperty("beside.models");
        if (dir == null || dir.isBlank()) {
            dir = System.getenv("PREPROCESS_MODELSDIR");
        }
        return Path.of(dir == null || dir.isBlank() ? "models" : dir);
    }

    @BeforeAll
    static void load() throws Exception {
        Path dir = modelsDir();
        assumeTrue(Files.isRegularFile(dir.resolve("yolox_s.onnx")) && Files.isRegularFile(dir.resolve("u2net.onnx")),
                "models not found in " + dir.toAbsolutePath() + " (scripts/fetch_models.*)");
        yolox = YoloxDetector.load(YoloxDetector.Spec.defaults(dir.resolve("yolox_s.onnx")));
        u2net = OnnxSaliencySegmenter.load(dir.resolve("u2net.onnx"), SaliencyCodec.Spec.U2NET, 1, true);
    }

    @AfterAll
    static void close() {
        if (yolox != null) {
            yolox.close();
        }
        if (u2net != null) {
            u2net.close();
        }
    }

    @Test
    void modelsAreThePinnedFilesWithTheExpectedShapes() {
        assertThat(yolox.name()).isEqualTo("yolox_s#c5c2d13e");
        assertThat(yolox.inputSize()).isEqualTo(640);
        assertThat(u2net.name()).isEqualTo("u2net#8d10d2f3");
    }

    @Test
    void anEmptyRoomHasNoDogOrCat() throws Exception {
        BufferedImage grey = new BufferedImage(800, 600, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = grey.createGraphics();
        g.setColor(new Color(180, 180, 180));
        g.fillRect(0, 0, 800, 600);
        g.dispose();

        assertThat(yolox.detect(grey)).isEmpty();
    }

    @Test
    void u2netFindsTheObjectInTheMiddle() throws Exception {
        BufferedImage picture = new BufferedImage(400, 300, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = picture.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(230, 230, 225));
        g.fillRect(0, 0, 400, 300);
        g.setColor(new Color(120, 70, 30));
        g.fillOval(120, 70, 160, 160);
        g.dispose();

        float[] alpha = u2net.segment(picture);

        assertThat(alpha).isNotNull().hasSize(400 * 300);
        double inside = mean(alpha, 400, 170, 120, 230, 180);
        double border = mean(alpha, 400, 0, 0, 400, 20);
        assertThat(inside).as("inside %.2f, border %.2f", inside, border).isGreaterThan(border + 0.3);
    }

    /**
     * The YOLOX convention (BGR, 0..255, top-left padding) on a real dog photo: it must score best among the four
     * channel-order x normalisation variants, and stay aligned when the photo is cropped off-centre.
     */
    @Test
    void conventionIsConfirmedOnARealDogPhoto() throws Exception {
        Path dog = PHOTOS.resolve("dog.jpg");
        assumeTrue(Files.isRegularFile(dog), "no team photo " + dog);
        DecodedPhoto photo = ImageDecoding.decode(dog, 1600, ImageDecoding.Limits.DEFAULT);
        Path model = modelsDir().resolve("yolox_s.onnx");

        float official = bestDogScore(photo.image(), YoloxCodec.ChannelOrder.BGR, false, model);
        float rgb = bestDogScore(photo.image(), YoloxCodec.ChannelOrder.RGB, false, model);
        float legacyRgb = bestDogScore(photo.image(), YoloxCodec.ChannelOrder.RGB, true, model);
        float legacyBgr = bestDogScore(photo.image(), YoloxCodec.ChannelOrder.BGR, true, model);

        assertThat(official).as("BGR raw %.3f, RGB raw %.3f, RGB legacy %.3f, BGR legacy %.3f", official, rgb,
                legacyRgb, legacyBgr).isGreaterThanOrEqualTo(0.7f)
                .isGreaterThanOrEqualTo(Math.max(rgb, Math.max(legacyRgb, legacyBgr)));

        // crop to a wide strip that keeps the dog off-centre: its box must move with the crop, not drift
        Box full = yolox.detect(photo.image()).get(0).box();
        int w = photo.image().getWidth();
        int h = photo.image().getHeight();
        int x0 = (int) Math.max(0, full.x1() - 10);
        int stripWidth = Math.min(w - x0, Math.max((int) full.width() * 2, 2 * h / 3));
        BufferedImage strip = ImageDecoding.draw(photo.image().getSubimage(x0, 0, stripWidth, h), stripWidth, h);
        List<Detection> inStrip = yolox.detect(strip);
        assertThat(inStrip).isNotEmpty();
        Box moved = new Box(full.x1() - x0, full.y1(), full.x2() - x0, full.y2());
        assertThat(inStrip.get(0).box().iou(moved)).as("box in the strip vs the moved full box").isGreaterThan(0.7f);
    }

    private static float bestDogScore(BufferedImage image, YoloxCodec.ChannelOrder order, boolean legacy, Path model)
            throws Exception {
        YoloxDetector.Spec d = YoloxDetector.Spec.defaults(model);
        YoloxDetector.Spec spec = new YoloxDetector.Spec(model, 0, order, legacy, false, d.classIds(), 0.01f,
                d.nmsIou(), d.containment(), d.threads(), d.cpuArena());
        try (YoloxDetector detector = YoloxDetector.load(spec)) {
            return (float) detector.detect(image).stream().filter(x -> x.classId() == YoloxCodec.COCO_DOG)
                    .mapToDouble(Detection::score).max().orElse(0);
        }
    }

    private static double mean(float[] alpha, int width, int x0, int y0, int x1, int y1) {
        double sum = 0;
        int n = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                sum += alpha[y * width + x];
                n++;
            }
        }
        return sum / n;
    }
}
