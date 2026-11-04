package com.example.mockbackend.preprocess;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;

class PhotoQualityTest {

    @Test
    void brightnessPercentileLooksOnlyInsideTheBox() {
        BufferedImage image = new BufferedImage(100, 100, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(50, 0, 50, 100); // left half black, right half white
        g.dispose();
        Box all = new Box(0, 0, 100, 100);

        assertThat(PhotoQuality.lumaPercentile(image, all, 0.9)).isEqualTo(255);
        assertThat(PhotoQuality.lumaPercentile(image, all, 0.1)).isZero();
        assertThat(PhotoQuality.lumaPercentile(image, new Box(0, 0, 50, 100), 0.9)).as("black half").isZero();
    }

    @Test
    void lumaUsesTheBt601Weights() {
        BufferedImage image = new BufferedImage(10, 10, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(0, 255, 0));
        g.fillRect(0, 0, 10, 10);
        g.dispose();

        assertThat(PhotoQuality.lumaPercentile(image, new Box(0, 0, 10, 10), 0.5)).isEqualTo(150); // 0.587 * 255
    }

    @Test
    void laplacianVarianceSeparatesSharpFromSmooth() {
        Box subject = new Box(100, 100, 500, 400);
        BufferedImage sharp = Pictures.scene(600, 500, subject, true, 1.0);
        BufferedImage smooth = Pictures.scene(600, 500, subject, false, 1.0);
        BufferedImage flat = new BufferedImage(600, 500, BufferedImage.TYPE_3BYTE_BGR);

        double sharpness = PhotoQuality.laplacianVariance(sharp, subject, 512);
        double smoothness = PhotoQuality.laplacianVariance(smooth, subject, 512);

        assertThat(sharpness).isGreaterThan(1000);
        assertThat(smoothness).isLessThan(5);
        assertThat(PhotoQuality.laplacianVariance(flat, subject, 512)).isZero();
    }

    @Test
    void sharpnessIsMeasuredAtTheReducedSize() {
        Box subject = new Box(0, 0, 1024, 1024);
        BufferedImage fine = Pictures.scene(1024, 1024, subject, true, 1.0); // 3 px cells

        double atFull = PhotoQuality.laplacianVariance(fine, subject, 2048);
        double reduced = PhotoQuality.laplacianVariance(fine, subject, 128); // cells shrink below a pixel: grey

        assertThat(reduced).isLessThan(atFull / 4);
    }

    @Test
    void aBoxThinnerThanTheKernelHasNoSharpness() {
        BufferedImage image = Pictures.scene(100, 100, new Box(0, 0, 100, 100), true, 1.0);
        assertThat(PhotoQuality.laplacianVariance(image, new Box(10, 10, 12, 90), 512)).isZero();
    }
}
