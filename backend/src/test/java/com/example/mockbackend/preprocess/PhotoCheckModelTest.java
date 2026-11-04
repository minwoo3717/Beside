package com.example.mockbackend.preprocess;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The first-photo check with the real detector on the CC0 test photos (src/test/resources/photos/SOURCES.md) and on
 * variants of dog.jpg made here, against the proposed thresholds. Local only: needs the models (PREPROCESS_MODELSDIR,
 * on Windows a JDK with msvcp140 14.40+); skipped in CI.
 */
@Tag("models")
class PhotoCheckModelTest {
    private static YoloxDetector yolox;
    private static PhotoCheck check;

    @BeforeAll
    static void load() throws Exception {
        Path models = OnnxModelsTest.modelsDir();
        assumeTrue(Files.isRegularFile(models.resolve("yolox_s.onnx")), "no models in " + models.toAbsolutePath());
        assumeTrue(Files.isRegularFile(OnnxModelsTest.PHOTOS.resolve("dog.jpg")), "no test photos");
        yolox = YoloxDetector.load(YoloxDetector.Spec.defaults(models.resolve("yolox_s.onnx")));
        check = new PhotoCheck(yolox, PhotoCheck.Rules.DEFAULT, ImageDecoding.Limits.DEFAULT, 1600);
    }

    @AfterAll
    static void close() {
        if (yolox != null) {
            yolox.close();
        }
    }

    /** The expected decisions written in SOURCES.md (with the 2026-11-05 second-animal score of 0.40). */
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "dog.jpg, PASS",
            "dog-single-black.jpg, PASS",
            "dog-single-edge.jpg, PASS",
            "dogs-two-swimming.jpg, TOO_MANY_ANIMALS",
            "dog-blurry.jpg, IMAGE_TOO_BLURRY",
            "no-animal-room.jpg, NO_ANIMAL_DETECTED"})
    void theTestPhotosGetTheirExpectedDecision(String file, String expected) throws Exception {
        PhotoCheck.Result result = check.check(Files.readAllBytes(OnnxModelsTest.PHOTOS.resolve(file)));

        assertThat(decision(result)).as("%s: %s", file, result.detail()).isEqualTo(expected);
    }

    @Test
    void darkenedIsTooDark() throws Exception {
        BufferedImage dog = read("dog.jpg");
        assertThat(decision(check.check(jpeg(scaled(dog, 0.15))))).isEqualTo("IMAGE_TOO_DARK");
    }

    @Test
    void tinyIsTooSmall() throws Exception {
        BufferedImage dog = read("dog.jpg");
        BufferedImage tiny = resize(dog, dog.getWidth() * 240 / dog.getHeight(), 240);
        assertThat(decision(check.check(jpeg(tiny)))).isEqualTo("IMAGE_TOO_SMALL");
    }

    /**
     * ANIMAL_TOO_SMALL is for a dog the detector finds but that is under 224 original pixels. The same 200 px dog in a
     * 1600 x 1200 photo is 80 px in the 640 input and was not found at all (NO_ANIMAL_DETECTED, 2026-11-05): the
     * detector's own limit comes first there (docs/preprocess record).
     */
    @Test
    void aSmallDogInALowResolutionPhotoIsTooSmall() throws Exception {
        assertThat(decision(check.check(jpeg(dogOnField(800, 600, 200))))).isEqualTo("ANIMAL_TOO_SMALL");
        assertThat(decision(check.check(jpeg(dogOnField(1600, 1200, 200))))).as("below the detector's limit")
                .isEqualTo("NO_ANIMAL_DETECTED");
    }

    private static BufferedImage dogOnField(int width, int height, int dogHeight) throws Exception {
        BufferedImage dog = read("dog.jpg");
        BufferedImage field = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = field.createGraphics();
        g.setColor(new Color(120, 150, 110));
        g.fillRect(0, 0, width, height);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        int dogWidth = dog.getWidth() * dogHeight / dog.getHeight();
        g.drawImage(dog, (width - dogWidth) / 2, (height - dogHeight) / 2, dogWidth, dogHeight, null);
        g.dispose();
        return field;
    }

    @Test
    void aPhoneShotStoredSidewaysIsTurnedUprightFirst() throws Exception {
        BufferedImage dog = read("dog.jpg");
        byte[] sideways = Pictures.withOrientation(jpeg(rotateCounterClockwise(dog)), 6, ByteOrder.BIG_ENDIAN);

        PhotoCheck.Result result = check.check(sideways);

        assertThat(decision(result)).as(result.detail()).isEqualTo("PASS");
        assertThat(result.width()).isEqualTo(dog.getWidth());
        assertThat(result.height()).isEqualTo(dog.getHeight());
    }

    private static String decision(PhotoCheck.Result result) {
        return result.accepted() ? "PASS" : result.reason().name();
    }

    static BufferedImage read(String file) throws Exception {
        return ImageIO.read(OnnxModelsTest.PHOTOS.resolve(file).toFile());
    }

    private static byte[] jpeg(BufferedImage image) {
        BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.dispose();
        return Pictures.encode(rgb, "jpeg");
    }

    private static BufferedImage scaled(BufferedImage image, double light) {
        BufferedImage out = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                Color c = new Color(image.getRGB(x, y));
                out.setRGB(x, y, new Color((int) (c.getRed() * light), (int) (c.getGreen() * light),
                        (int) (c.getBlue() * light)).getRGB());
            }
        }
        return out;
    }

    private static BufferedImage resize(BufferedImage image, int width, int height) {
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(image, 0, 0, width, height, null);
        g.dispose();
        return out;
    }

    /** Stored as a camera that was turned 90 degrees clockwise would store it (EXIF 6 turns it back). */
    private static BufferedImage rotateCounterClockwise(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        BufferedImage out = new BufferedImage(h, w, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                out.setRGB(y, w - 1 - x, image.getRGB(x, y));
            }
        }
        return out;
    }
}
