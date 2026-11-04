package com.example.mockbackend.preprocess;

import com.example.mockbackend.preprocess.AnimalDetector.Detection;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * The arithmetic around a YOLOX model, without ONNX Runtime: the letterboxed input tensor and the decoding of the raw
 * output (grid offsets, strides, objectness x class score, suppression). Follows the YOLOX repository's
 * {@code preproc} and {@code demo_postprocess} (0.1.1 and later): BGR, values 0..255, the photo resized by
 * {@code r = min(S/h, S/w)} and pasted at the top-left of an S x S canvas filled with 114.
 */
public final class YoloxCodec {
    public static final int COCO_CAT = 15;
    public static final int COCO_DOG = 16;
    /** Strides of the three YOLOX heads, in the order of the output rows. */
    static final int[] STRIDES = {8, 16, 32};
    static final float PAD = 114f;
    private static final float[] LEGACY_MEAN = {0.485f, 0.456f, 0.406f};
    private static final float[] LEGACY_STD = {0.229f, 0.224f, 0.225f};

    public enum ChannelOrder { BGR, RGB }

    /**
     * @param chw   the input tensor [1, 3, S, S] flattened channel by channel
     * @param size  S
     * @param ratio r: pixels of the input per pixel of the photo
     */
    public record Letterbox(float[] chw, int size, float ratio) {
    }

    private YoloxCodec() {
    }

    /**
     * @param bgr             TYPE_3BYTE_BGR photo
     * @param legacyNormalize exports before YOLOX 0.1.1: RGB, divided by 255, ImageNet mean and standard deviation
     */
    public static Letterbox letterbox(BufferedImage bgr, int size, ChannelOrder order, boolean legacyNormalize) {
        int w = bgr.getWidth();
        int h = bgr.getHeight();
        float r = Math.min((float) size / h, (float) size / w);
        int rw = Math.max(1, Math.min(size, (int) (w * r)));
        int rh = Math.max(1, Math.min(size, (int) (h * r)));
        byte[] pixels = ((DataBufferByte) resize(bgr, rw, rh).getRaster().getDataBuffer()).getData();

        int plane = size * size;
        float[] chw = new float[3 * plane];
        for (int c = 0; c < 3; c++) {
            Arrays.fill(chw, c * plane, (c + 1) * plane, value(PAD, c, order, legacyNormalize));
        }
        for (int y = 0; y < rh; y++) {
            for (int x = 0; x < rw; x++) {
                int p = (y * rw + x) * 3; // B, G, R
                int at = y * size + x;
                float b = pixels[p] & 0xFF;
                float g = pixels[p + 1] & 0xFF;
                float red = pixels[p + 2] & 0xFF;
                float first = order == ChannelOrder.BGR ? b : red;
                float third = order == ChannelOrder.BGR ? red : b;
                chw[at] = value(first, 0, order, legacyNormalize);
                chw[plane + at] = value(g, 1, order, legacyNormalize);
                chw[2 * plane + at] = value(third, 2, order, legacyNormalize);
            }
        }
        return new Letterbox(chw, size, r);
    }

    /** One step of bilinear resampling, as cv2.resize(INTER_LINEAR) in YOLOX's preproc. */
    private static BufferedImage resize(BufferedImage source, int width, int height) {
        if (source.getWidth() == width && source.getHeight() == height
                && source.getType() == BufferedImage.TYPE_3BYTE_BGR) {
            return source;
        }
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = target.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(source, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        return target;
    }

    private static float value(float v, int channel, ChannelOrder order, boolean legacy) {
        if (!legacy) {
            return v;
        }
        // the legacy statistics are per R, G, B
        int rgbIndex = order == ChannelOrder.RGB ? channel : 2 - channel;
        return (v / 255f - LEGACY_MEAN[rgbIndex]) / LEGACY_STD[rgbIndex];
    }

    /**
     * Candidates of the wanted classes from the raw output.
     *
     * @param out             output [1, rows, cols] flattened; cols = 4 box + 1 objectness + classes
     * @param size            S of the letterboxed input
     * @param ratio           {@link Letterbox#ratio()}
     * @param imgW            width of the photo that was letterboxed (boxes are clipped to it)
     * @param imgH            its height
     * @param classIds        classes to keep (COCO indices)
     * @param minScore        objectness x class probability below this is dropped
     * @param alreadyDecoded  true for exports made with decoding inside the model (rows are cx, cy, w, h in input
     *                        pixels); false for the official exports (grid offsets and log sizes per stride)
     * @return candidates in photo pixels, not yet suppressed, best score first
     */
    public static List<Detection> decode(float[] out, int rows, int cols, int size, float ratio, int imgW, int imgH,
                                         int[] classIds, float minScore, boolean alreadyDecoded) {
        if (cols < 6 || out.length != rows * cols) {
            throw new IllegalArgumentException("YOLOX output must be [1, rows, 5 + classes]; got " + out.length
                    + " values for " + rows + " x " + cols);
        }
        for (int classId : classIds) {
            if (classId < 0 || 5 + classId >= cols) {
                throw new IllegalArgumentException("class " + classId + " is not in an output of " + cols + " columns");
            }
        }
        int expected = 0;
        for (int stride : STRIDES) {
            expected += (size / stride) * (size / stride);
        }
        if (!alreadyDecoded && rows != expected) {
            throw new IllegalArgumentException("a raw YOLOX output for input " + size + " has " + expected
                    + " rows, got " + rows + " (decoded-output export?)");
        }

        List<Detection> found = new ArrayList<>();
        int row = 0;
        for (int s = 0; s < STRIDES.length && (row < rows); s++) {
            int stride = STRIDES[s];
            int grid = alreadyDecoded ? 0 : size / stride;
            int cells = alreadyDecoded ? rows : grid * grid;
            for (int i = 0; i < cells; i++, row++) {
                int o = row * cols;
                float obj = out[o + 4];
                int bestClass = -1;
                float best = 0f;
                for (int classId : classIds) {
                    float score = obj * out[o + 5 + classId];
                    if (score > best) {
                        best = score;
                        bestClass = classId;
                    }
                }
                if (bestClass < 0 || best < minScore) {
                    continue;
                }
                float cx;
                float cy;
                float w;
                float h;
                if (alreadyDecoded) {
                    cx = out[o];
                    cy = out[o + 1];
                    w = out[o + 2];
                    h = out[o + 3];
                } else {
                    int gx = i % grid;
                    int gy = i / grid;
                    cx = (out[o] + gx) * stride;
                    cy = (out[o + 1] + gy) * stride;
                    w = (float) Math.exp(out[o + 2]) * stride;
                    h = (float) Math.exp(out[o + 3]) * stride;
                }
                Box box = new Box((cx - w / 2) / ratio, (cy - h / 2) / ratio, (cx + w / 2) / ratio,
                        (cy + h / 2) / ratio).clip(imgW, imgH);
                if (box.area() > 0) {
                    found.add(new Detection(box, bestClass, best));
                }
            }
            if (alreadyDecoded) {
                break;
            }
        }
        found.sort(Comparator.comparingDouble((Detection d) -> d.score()).reversed());
        return found;
    }

    /**
     * Class-agnostic suppression: a box is dropped when it overlaps a kept, better box by more than {@code iou}, or
     * when at least {@code containment} of its own area lies inside one (a head box inside a body box is the same
     * animal). A dog and a cat on the same animal are one animal.
     *
     * @return kept detections, best score first
     */
    public static List<Detection> nms(List<Detection> candidates, float iou, float containment) {
        List<Detection> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparingDouble((Detection d) -> d.score()).reversed());
        List<Detection> kept = new ArrayList<>();
        for (Detection candidate : sorted) {
            boolean same = false;
            for (Detection k : kept) {
                Box box = candidate.box();
                float inside = box.area() <= 0 ? 1f : box.intersection(k.box()) / box.area();
                if (box.iou(k.box()) > iou || inside >= containment) {
                    same = true;
                    break;
                }
            }
            if (!same) {
                kept.add(candidate);
            }
        }
        return kept;
    }
}
