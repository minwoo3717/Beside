package com.example.mockbackend.preprocess;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The cutout with the real models on the CC0 dog photos: a square with transparent corners, the dog filling about
 * {@code fill} of it and centred, also when the dog is cut by the photo's edge. Local only, like PhotoCheckModelTest.
 */
@Tag("models")
class CutoutModelTest {
    private static YoloxDetector yolox;
    private static OnnxSaliencySegmenter u2net;

    @BeforeAll
    static void load() throws Exception {
        Path models = OnnxModelsTest.modelsDir();
        assumeTrue(Files.isRegularFile(models.resolve("yolox_s.onnx")) && Files.isRegularFile(models.resolve("u2net.onnx")),
                "no models in " + models.toAbsolutePath());
        assumeTrue(Files.isRegularFile(OnnxModelsTest.PHOTOS.resolve("dog.jpg")), "no test photos");
        yolox = YoloxDetector.load(YoloxDetector.Spec.defaults(models.resolve("yolox_s.onnx")));
        u2net = OnnxSaliencySegmenter.load(models.resolve("u2net.onnx"), SaliencyCodec.Spec.U2NET, 1, true);
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

    @ParameterizedTest
    @ValueSource(strings = {"dog.jpg", "dog-single-black.jpg", "dog-single-edge.jpg"})
    void theDogIsCutOutCentredOnATransparentSquare(String file) throws Exception {
        DecodedPhoto photo = ImageDecoding.decode(OnnxModelsTest.PHOTOS.resolve(file), 2048, ImageDecoding.Limits.DEFAULT);

        Cutout.Result result = new Cutout(yolox, u2net, Cutout.Rules.DEFAULT).make(photo);

        assertThat(result.status()).as(result.detail()).isEqualTo(Cutout.APPLIED);
        BufferedImage square = result.image();
        int side = square.getWidth();
        assertThat(square.getHeight()).isEqualTo(side);
        assertThat(side).isLessThanOrEqualTo(Cutout.Rules.DEFAULT.maxSide());
        for (int[] corner : new int[][]{{0, 0}, {side - 1, 0}, {0, side - 1}, {side - 1, side - 1}}) {
            assertThat(alpha(square, corner[0], corner[1])).as("corner %d,%d", corner[0], corner[1]).isZero();
        }
        int[] b = opaqueBounds(square);
        double longSide = Math.max(b[2] - b[0] + 1, b[3] - b[1] + 1);
        assertThat(longSide / side).as("fill").isCloseTo(Cutout.Rules.DEFAULT.fill(), within(0.05));
        assertThat((b[0] + b[2]) / 2.0 / side).as("centred across").isCloseTo(0.5, within(0.05));
        assertThat((b[1] + b[3]) / 2.0 / side).as("centred down").isCloseTo(0.5, within(0.05));
        assertThat(result.foreground()).isBetween(0.05, 0.95);
    }

    @ParameterizedTest
    @ValueSource(strings = {"dog.jpg"})
    void keepingTheBackgroundStillGivesASquare(String file) throws Exception {
        DecodedPhoto photo = ImageDecoding.decode(OnnxModelsTest.PHOTOS.resolve(file), 2048, ImageDecoding.Limits.DEFAULT);
        Cutout.Rules keep = new Cutout.Rules(0.40f, 0.15, 0.85, 1024, 0.05, 0.95, true, Cutout.Background.KEEP);

        Cutout.Result result = new Cutout(yolox, null, keep).make(photo);

        assertThat(result.status()).isEqualTo(Cutout.APPLIED);
        BufferedImage square = result.image();
        assertThat(square.getWidth()).isEqualTo(square.getHeight());
        Color centre = new Color(square.getRGB(square.getWidth() / 2, square.getHeight() / 2), true);
        assertThat(centre.getAlpha()).isEqualTo(255);
    }

    private static int alpha(BufferedImage image, int x, int y) {
        return (image.getRGB(x, y) >>> 24) & 0xFF;
    }

    /** min x, min y, max x, max y of the pixels with alpha >= 128. */
    private static int[] opaqueBounds(BufferedImage image) {
        int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, -1, -1};
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (alpha(image, x, y) >= 128) {
                    b[0] = Math.min(b[0], x);
                    b[1] = Math.min(b[1], y);
                    b[2] = Math.max(b[2], x);
                    b[3] = Math.max(b[3], y);
                }
            }
        }
        return b;
    }
}
