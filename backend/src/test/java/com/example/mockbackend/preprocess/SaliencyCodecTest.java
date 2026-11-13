package com.example.mockbackend.preprocess;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class SaliencyCodecTest {
    private static final SaliencyCodec.Spec SMALL_U2NET = new SaliencyCodec.Spec(4,
            new float[]{0.485f, 0.456f, 0.406f}, new float[]{0.229f, 0.224f, 0.225f}, true, 0, false);

    @Test
    void tensorIsRgbStretchedDividedByTheMaxAndNormalised() {
        BufferedImage red = new BufferedImage(10, 6, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = red.createGraphics();
        g.setColor(new Color(220, 30, 30));
        g.fillRect(0, 0, 10, 6);
        g.dispose();

        float[] chw = SaliencyCodec.tensor(red, SMALL_U2NET);

        assertThat(chw).hasSize(3 * 4 * 4); // stretched to 4 x 4 whatever the shape
        float r = 1f;                // 220 / max 220
        float gb = 30f / 220f;
        assertThat(chw[0]).as("R").isCloseTo((r - 0.485f) / 0.229f, within(1e-3f));
        assertThat(chw[16]).as("G").isCloseTo((gb - 0.456f) / 0.224f, within(1e-3f));
        assertThat(chw[32]).as("B").isCloseTo((gb - 0.406f) / 0.225f, within(1e-3f));
    }

    @Test
    void dividingBy255IsASetting() {
        BufferedImage grey = new BufferedImage(4, 4, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = grey.createGraphics();
        g.setColor(new Color(51, 51, 51));
        g.fillRect(0, 0, 4, 4);
        g.dispose();
        SaliencyCodec.Spec by255 = new SaliencyCodec.Spec(4, new float[]{0.5f, 0.5f, 0.5f},
                new float[]{1f, 1f, 1f}, false, 0, false);

        assertThat(SaliencyCodec.tensor(grey, by255)[0]).isCloseTo(0.2f - 0.5f, within(1e-4f));
    }

    @Test
    void maskIsMinMaxNormalisedAndResized() {
        float[] plane = {0f, 2f, 0f, 2f}; // 2 x 2: left column 0, right column 2

        float[] alpha = SaliencyCodec.mask(plane, 2, SMALL_U2NET, 8, 4);

        assertThat(alpha).hasSize(32);
        assertThat(alpha[0]).as("left edge").isCloseTo(0f, within(1e-6f));
        assertThat(alpha[7]).as("right edge").isCloseTo(1f, within(1e-6f));
        assertThat(alpha[3]).as("middle").isBetween(0f, 1f);
        for (float a : alpha) {
            assertThat(a).isBetween(0f, 1f);
        }
    }

    @Test
    void sigmoidIsAppliedToLogitOutputs() {
        SaliencyCodec.Spec logits = new SaliencyCodec.Spec(2, new float[]{0, 0, 0}, new float[]{1, 1, 1}, true, 0, true);
        float[] plane = {-10f, 10f, -10f, 10f};

        float[] alpha = SaliencyCodec.mask(plane, 2, logits, 2, 2);

        assertThat(alpha[0]).isCloseTo(0f, within(1e-6f));
        assertThat(alpha[1]).isCloseTo(1f, within(1e-6f));
    }

    @Test
    void aFlatOutputHasNoForeground() {
        assertThat(SaliencyCodec.mask(new float[]{0.3f, 0.3f, 0.3f, 0.3f}, 2, SMALL_U2NET, 4, 4)).isNull();
    }
}
