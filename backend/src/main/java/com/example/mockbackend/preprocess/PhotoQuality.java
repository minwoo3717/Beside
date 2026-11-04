package com.example.mockbackend.preprocess;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;

/** Brightness and sharpness inside a box of a decoded photo (TYPE_3BYTE_BGR). */
public final class PhotoQuality {

    private PhotoQuality() {
    }

    /**
     * Percentile {@code p} (0..1) of the luma (BT.601: 0.299 R + 0.587 G + 0.114 B) inside {@code region}, 0..255.
     * The check uses the 90th percentile, not the mean: a black cat in good light has a low mean but bright
     * surroundings and highlights in its box; a photo taken in the dark has neither.
     */
    public static int lumaPercentile(BufferedImage bgr, Box region, double p) {
        int[] bounds = bounds(bgr, region);
        int x0 = bounds[0];
        int y0 = bounds[1];
        int x1 = bounds[2];
        int y1 = bounds[3];
        if (x1 <= x0 || y1 <= y0) {
            return 0;
        }
        byte[] pixels = pixels(bgr);
        int width = bgr.getWidth();
        int[] histogram = new int[256];
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                histogram[luma(pixels, (y * width + x) * 3)]++;
            }
        }
        long total = (long) (x1 - x0) * (y1 - y0);
        long wanted = Math.max(1, (long) Math.ceil(p * total));
        long seen = 0;
        for (int v = 0; v < 256; v++) {
            seen += histogram[v];
            if (seen >= wanted) {
                return v;
            }
        }
        return 255;
    }

    /**
     * Variance of the 4-neighbour Laplacian of the luma inside {@code region}, after reducing the region so that its
     * long side is at most {@code maxLongSide} (never enlarged). The usual blur measure (cv2.Laplacian(...).var()),
     * taken near the size the model sees, so detail too fine to survive that size does not count as sharpness.
     *
     * @return 0 when the region is thinner than the 3 x 3 kernel
     */
    public static double laplacianVariance(BufferedImage bgr, Box region, int maxLongSide) {
        int[] bounds = bounds(bgr, region);
        int w = bounds[2] - bounds[0];
        int h = bounds[3] - bounds[1];
        if (w < 3 || h < 3) {
            return 0;
        }
        BufferedImage reduced = ImageDecoding.toBgr(bgr.getSubimage(bounds[0], bounds[1], w, h), maxLongSide);
        int rw = reduced.getWidth();
        int rh = reduced.getHeight();
        if (rw < 3 || rh < 3) {
            return 0;
        }
        byte[] pixels = pixels(reduced);
        int[] luma = new int[rw * rh];
        for (int i = 0; i < luma.length; i++) {
            luma[i] = luma(pixels, i * 3);
        }
        double sum = 0;
        double sumSquares = 0;
        long n = 0;
        for (int y = 1; y < rh - 1; y++) {
            for (int x = 1; x < rw - 1; x++) {
                int at = y * rw + x;
                int lap = luma[at - 1] + luma[at + 1] + luma[at - rw] + luma[at + rw] - 4 * luma[at];
                sum += lap;
                sumSquares += (double) lap * lap;
                n++;
            }
        }
        double mean = sum / n;
        return Math.max(0, sumSquares / n - mean * mean);
    }

    /** Integer BT.601 luma of the pixel whose B byte is at {@code at}. */
    static int luma(byte[] bgr, int at) {
        int b = bgr[at] & 0xFF;
        int g = bgr[at + 1] & 0xFF;
        int r = bgr[at + 2] & 0xFF;
        return (299 * r + 587 * g + 114 * b + 500) / 1000;
    }

    private static int[] bounds(BufferedImage image, Box region) {
        Box clipped = region.clip(image.getWidth(), image.getHeight());
        return new int[]{(int) Math.floor(clipped.x1()), (int) Math.floor(clipped.y1()),
                (int) Math.ceil(clipped.x2()), (int) Math.ceil(clipped.y2())};
    }

    private static byte[] pixels(BufferedImage bgr) {
        if (bgr.getType() != BufferedImage.TYPE_3BYTE_BGR) {
            throw new IllegalArgumentException("expected TYPE_3BYTE_BGR, got type " + bgr.getType());
        }
        return ((DataBufferByte) bgr.getRaster().getDataBuffer()).getData();
    }
}
