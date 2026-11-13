package com.example.mockbackend.preprocess;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;

/**
 * The arithmetic around a salient-object model (U2Net, u2netp, ISNet), without ONNX Runtime. Follows rembg's sessions:
 * the crop is stretched to S x S, divided by its largest value, normalised per channel in RGB order; the first output
 * plane is min-max normalised and resized back to the crop to become its alpha.
 */
public final class SaliencyCodec {

    /**
     * @param inputSize   S of the square input
     * @param mean        per R, G, B after the division
     * @param std         per R, G, B
     * @param divideByMax true: divide by the crop's largest channel value (rembg); false: by 255
     * @param outputIndex which model output holds the mask (U2Net: 0, the fused map)
     * @param sigmoid     the output is logits (BiRefNet-style exports), not probabilities
     */
    public record Spec(int inputSize, float[] mean, float[] std, boolean divideByMax, int outputIndex, boolean sigmoid) {
        public static final Spec U2NET = new Spec(320, new float[]{0.485f, 0.456f, 0.406f},
                new float[]{0.229f, 0.224f, 0.225f}, true, 0, false);
    }

    private SaliencyCodec() {
    }

    /** Input tensor [1, 3, S, S] flattened channel by channel (R, G, B). */
    public static float[] tensor(BufferedImage bgr, Spec spec) {
        int s = spec.inputSize();
        BufferedImage square = stretch(bgr, s);
        byte[] pixels = ((DataBufferByte) square.getRaster().getDataBuffer()).getData();
        float divisor = 255f;
        if (spec.divideByMax()) {
            int max = 0;
            for (byte b : pixels) {
                max = Math.max(max, b & 0xFF);
            }
            divisor = Math.max(max, 1e-6f);
        }
        int plane = s * s;
        float[] chw = new float[3 * plane];
        for (int i = 0; i < plane; i++) {
            float b = (pixels[i * 3] & 0xFF) / divisor;
            float g = (pixels[i * 3 + 1] & 0xFF) / divisor;
            float r = (pixels[i * 3 + 2] & 0xFF) / divisor;
            chw[i] = (r - spec.mean()[0]) / spec.std()[0];
            chw[plane + i] = (g - spec.mean()[1]) / spec.std()[1];
            chw[2 * plane + i] = (b - spec.mean()[2]) / spec.std()[2];
        }
        return chw;
    }

    /**
     * Alpha for a {@code width} x {@code height} crop from one S x S output plane: sigmoid when the spec says so,
     * min-max normalisation, bilinear resize.
     *
     * @return null when the plane is flat (max equals min): the model saw no foreground
     */
    public static float[] mask(float[] plane, int size, Spec spec, int width, int height) {
        float[] values = plane.clone();
        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        for (int i = 0; i < values.length; i++) {
            if (spec.sigmoid()) {
                values[i] = (float) (1.0 / (1.0 + Math.exp(-values[i])));
            }
            min = Math.min(min, values[i]);
            max = Math.max(max, values[i]);
        }
        if (!(max - min > 1e-6f)) {
            return null;
        }
        float range = max - min;
        for (int i = 0; i < values.length; i++) {
            values[i] = (values[i] - min) / range;
        }
        return resize(values, size, size, width, height);
    }

    /** Bilinear resize of a float plane, pixel centres aligned. */
    static float[] resize(float[] src, int sw, int sh, int dw, int dh) {
        float[] dst = new float[dw * dh];
        for (int y = 0; y < dh; y++) {
            float sy = Math.max(0f, Math.min(sh - 1f, (y + 0.5f) * sh / dh - 0.5f));
            int y0 = (int) sy;
            int y1 = Math.min(sh - 1, y0 + 1);
            float fy = sy - y0;
            for (int x = 0; x < dw; x++) {
                float sx = Math.max(0f, Math.min(sw - 1f, (x + 0.5f) * sw / dw - 0.5f));
                int x0 = (int) sx;
                int x1 = Math.min(sw - 1, x0 + 1);
                float fx = sx - x0;
                float top = src[y0 * sw + x0] * (1 - fx) + src[y0 * sw + x1] * fx;
                float bottom = src[y1 * sw + x0] * (1 - fx) + src[y1 * sw + x1] * fx;
                dst[y * dw + x] = Math.max(0f, Math.min(1f, top * (1 - fy) + bottom * fy));
            }
        }
        return dst;
    }

    /** The crop stretched to S x S (aspect not kept, as the models were trained), halving first when much bigger. */
    private static BufferedImage stretch(BufferedImage bgr, int size) {
        BufferedImage current = bgr;
        if (Math.max(bgr.getWidth(), bgr.getHeight()) > 2 * size) {
            current = ImageDecoding.toBgr(bgr, 2 * size);
        }
        BufferedImage square = new BufferedImage(size, size, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = square.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(current, 0, 0, size, size, null);
        } finally {
            g.dispose();
        }
        return square;
    }
}
