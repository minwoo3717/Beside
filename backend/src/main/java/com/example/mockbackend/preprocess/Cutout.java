package com.example.mockbackend.preprocess;

import com.example.mockbackend.preprocess.AnimalDetector.Detection;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The input AnimalLift gets instead of the stretched photo: the main animal cropped with a margin, its background
 * removed (or kept, for comparison), centred on a transparent square so that it fills {@code fill} of the side, at most
 * {@code maxSide} pixels. Python composites transparency on white and resizes the square to its model size, which no
 * longer distorts the animal.
 * <p>
 * Never throws: when a step fails, the result is FALLBACK with a reason and the caller sends the original photo.
 */
public final class Cutout {
    static final String VERSION = "cutout1";
    public static final String APPLIED = "APPLIED";

    public enum Background { REMOVE, KEEP }

    /**
     * Settings; proposals (제안값) set by preprocess.cutout.* on the server.
     *
     * @param minAnimalScore       an animal counts from this score; the largest one counted is cut out
     * @param margin               the crop is the animal's box grown by this fraction on every side
     * @param fill                 the animal's long side over the square's side
     * @param maxSide              the square is reduced to at most this many pixels (never enlarged)
     * @param minForeground        less of the crop than this is foreground: FALLBACK:MASK_EMPTY
     * @param maxForeground        more than this: FALLBACK:MASK_FULL (the model took everything)
     * @param largestComponentOnly keep the largest connected foreground region (and 3 px around it) only
     * @param background           REMOVE (cut out) or KEEP (crop and pad only)
     */
    public record Rules(float minAnimalScore, double margin, double fill, int maxSide, double minForeground,
                        double maxForeground, boolean largestComponentOnly, Background background) {
        public static final Rules DEFAULT = new Rules(0.40f, 0.15, 0.85, 1024, 0.05, 0.95, true, Background.REMOVE);
    }

    /**
     * @param status     APPLIED, or FALLBACK:NO_ANIMAL|MASK_EMPTY|MASK_FULL|ERROR
     * @param image      the square, TYPE_INT_ARGB; null on fallback
     * @param animal     the animal that was cut out, in the pixels of the decoded photo
     * @param crop       the crop around it, same pixels
     * @param foreground share of the crop that is foreground (alpha at least 0.5); -1 when the background is kept
     */
    public record Result(String status, BufferedImage image, Detection animal, Box crop, double foreground,
                         long detectMs, long segmentMs, long totalMs, String detail) {
        public boolean applied() {
            return APPLIED.equals(status);
        }
    }

    private final AnimalDetector detector;
    private final ForegroundSegmenter segmenter;
    private final Rules rules;

    /** {@code segmenter} is not used (and may be null) when the background is kept. */
    public Cutout(AnimalDetector detector, ForegroundSegmenter segmenter, Rules rules) {
        this.detector = detector;
        this.segmenter = segmenter;
        this.rules = rules;
    }

    public Rules rules() {
        return rules;
    }

    /** What made the square: models and settings, at most 128 characters (the preprocessVersion column). */
    public String version() {
        boolean remove = rules.background() == Background.REMOVE;
        StringBuilder v = new StringBuilder(VERSION).append(" bg=").append(rules.background().name().toLowerCase(Locale.ROOT))
                .append(" det=").append(detector.name());
        if (remove) {
            v.append(" seg=").append(segmenter == null ? "none" : segmenter.name());
        }
        v.append(String.format(Locale.ROOT, " m=%.2f f=%.2f s=%d", rules.margin(), rules.fill(), rules.maxSide()));
        if (remove && rules.largestComponentOnly()) {
            v.append(" lcc=1");
        }
        return v.length() > 128 ? v.substring(0, 128) : v.toString();
    }

    public Result make(DecodedPhoto photo) {
        long start = System.nanoTime();
        List<Detection> animals;
        try {
            animals = detector.detect(photo.image());
        } catch (PreprocessException | RuntimeException e) {
            return fallback("ERROR", "detector: " + e.getMessage(), null, null, ms(start), 0, start);
        }
        long detectMs = ms(start);
        Detection main = null;
        for (Detection d : animals) {
            if (d.score() >= rules.minAnimalScore() && (main == null || d.box().area() > main.box().area())) {
                main = d;
            }
        }
        if (main == null) {
            return fallback("NO_ANIMAL", PhotoCheck.describe(animals), null, null, detectMs, 0, start);
        }

        BufferedImage image = photo.image();
        Box crop = main.box().grow(rules.margin()).clip(image.getWidth(), image.getHeight());
        int x0 = (int) Math.floor(crop.x1());
        int y0 = (int) Math.floor(crop.y1());
        int cw = (int) Math.ceil(crop.x2()) - x0;
        int ch = (int) Math.ceil(crop.y2()) - y0;
        if (cw < 2 || ch < 2) {
            return fallback("NO_ANIMAL", "the animal's box is empty", main, crop, detectMs, 0, start);
        }
        BufferedImage cropImage = ImageDecoding.draw(image.getSubimage(x0, y0, cw, ch), cw, ch);

        long segmentStart = System.nanoTime();
        float[] alpha = null;
        double foreground = -1;
        Box tight;
        if (rules.background() == Background.KEEP) {
            tight = new Box(main.box().x1() - x0, main.box().y1() - y0, main.box().x2() - x0, main.box().y2() - y0);
        } else {
            try {
                alpha = segmenter.segment(cropImage);
            } catch (PreprocessException | RuntimeException e) {
                return fallback("ERROR", "segmenter: " + e.getMessage(), main, crop, detectMs, ms(segmentStart), start);
            }
            if (alpha == null) {
                return fallback("MASK_EMPTY", "the segmenter saw no foreground", main, crop, detectMs,
                        ms(segmentStart), start);
            }
            if (alpha.length != cw * ch) {
                return fallback("ERROR", "mask has " + alpha.length + " values for a " + cw + "x" + ch + " crop", main,
                        crop, detectMs, ms(segmentStart), start);
            }
            if (rules.largestComponentOnly()) {
                keepLargestRegion(alpha, cw, ch, 3);
            }
            int[] bounds = new int[]{Integer.MAX_VALUE, Integer.MAX_VALUE, -1, -1};
            long count = 0;
            for (int y = 0; y < ch; y++) {
                for (int x = 0; x < cw; x++) {
                    if (alpha[y * cw + x] >= 0.5f) {
                        count++;
                        bounds[0] = Math.min(bounds[0], x);
                        bounds[1] = Math.min(bounds[1], y);
                        bounds[2] = Math.max(bounds[2], x);
                        bounds[3] = Math.max(bounds[3], y);
                    }
                }
            }
            foreground = (double) count / ((long) cw * ch);
            if (foreground < rules.minForeground()) {
                return fallback("MASK_EMPTY", String.format(Locale.ROOT, "foreground %.3f < %.3f", foreground,
                        rules.minForeground()), main, crop, detectMs, ms(segmentStart), start);
            }
            if (foreground > rules.maxForeground()) {
                return fallback("MASK_FULL", String.format(Locale.ROOT, "foreground %.3f > %.3f", foreground,
                        rules.maxForeground()), main, crop, detectMs, ms(segmentStart), start);
            }
            tight = new Box(bounds[0], bounds[1], bounds[2] + 1, bounds[3] + 1);
        }
        long segmentMs = ms(segmentStart);

        BufferedImage square = compose(cropImage, alpha, tight, rules.fill());
        if (square.getWidth() > rules.maxSide()) {
            square = reduce(square, rules.maxSide());
        }
        String detail = String.format(Locale.ROOT, "%s %.2f, square %d px%s", PhotoCheck.className(main.classId()),
                main.score(), square.getWidth(), foreground >= 0
                        ? String.format(Locale.ROOT, ", foreground %.2f", foreground) : ", background kept");
        return new Result(APPLIED, square, main, crop, foreground, detectMs, segmentMs, ms(start), detail);
    }

    /** PNG through a temporary file and a move, so nobody reads half a file. ImageIO writes no EXIF chunk. */
    public static void writePng(BufferedImage rgba, Path target) throws IOException {
        Path dir = target.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path temp = Files.createTempFile(dir, "cutout-", ".tmp");
        try {
            if (!ImageIO.write(rgba, "png", temp.toFile())) {
                throw new IOException("no PNG writer");
            }
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * The crop on a transparent square whose side is the tight box's long side / fill, the tight box centred.
     * Transparent pixels are white (alpha 0) so that a reader ignoring alpha still sees a white background.
     */
    static BufferedImage compose(BufferedImage crop, float[] alpha, Box tight, double fill) {
        int side = Math.max(1, (int) Math.ceil(tight.longSide() / fill));
        BufferedImage canvas = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB);
        int[] out = ((DataBufferInt) canvas.getRaster().getDataBuffer()).getData();
        Arrays.fill(out, 0x00FFFFFF);
        int dx = (int) Math.round(side / 2.0 - tight.centerX());
        int dy = (int) Math.round(side / 2.0 - tight.centerY());
        byte[] pixels = ((DataBufferByte) crop.getRaster().getDataBuffer()).getData();
        int cw = crop.getWidth();
        int ch = crop.getHeight();
        for (int y = 0; y < ch; y++) {
            int ty = y + dy;
            if (ty < 0 || ty >= side) {
                continue;
            }
            for (int x = 0; x < cw; x++) {
                int tx = x + dx;
                if (tx < 0 || tx >= side) {
                    continue;
                }
                int a = alpha == null ? 255 : Math.round(alpha[y * cw + x] * 255f);
                if (a <= 0) {
                    continue;
                }
                int i = (y * cw + x) * 3;
                int b = pixels[i] & 0xFF;
                int g = pixels[i + 1] & 0xFF;
                int r = pixels[i + 2] & 0xFF;
                out[ty * side + tx] = (Math.min(255, a) << 24) | (r << 16) | (g << 8) | b;
            }
        }
        return canvas;
    }

    /** Square down to {@code maxSide} in halving steps, keeping the alpha channel. */
    static BufferedImage reduce(BufferedImage square, int maxSide) {
        BufferedImage current = square;
        int side = square.getWidth();
        while (side / 2 >= maxSide && side > maxSide * 2) {
            side /= 2;
            current = drawArgb(current, side);
        }
        return drawArgb(current, maxSide);
    }

    private static BufferedImage drawArgb(BufferedImage source, int side) {
        BufferedImage target = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = target.createGraphics();
        try {
            g.setComposite(AlphaComposite.Src);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(source, 0, 0, side, side, null);
        } finally {
            g.dispose();
        }
        return target;
    }

    /**
     * Keeps the largest 4-connected region of alpha >= 0.5 and everything within {@code grow} pixels of it (the soft
     * edge of fur); everything else becomes transparent (a second object the model also found salient).
     */
    static void keepLargestRegion(float[] alpha, int w, int h, int grow) {
        int n = w * h;
        int[] label = new int[n];
        int[] queue = new int[n];
        int best = 0;
        int bestSize = 0;
        int next = 0;
        for (int start = 0; start < n; start++) {
            if (alpha[start] < 0.5f || label[start] != 0) {
                continue;
            }
            int id = ++next;
            int head = 0;
            int tail = 0;
            queue[tail++] = start;
            label[start] = id;
            while (head < tail) {
                int p = queue[head++];
                int x = p % w;
                int y = p / w;
                if (x > 0) tail = visit(alpha, label, queue, tail, p - 1, id);
                if (x < w - 1) tail = visit(alpha, label, queue, tail, p + 1, id);
                if (y > 0) tail = visit(alpha, label, queue, tail, p - w, id);
                if (y < h - 1) tail = visit(alpha, label, queue, tail, p + w, id);
            }
            if (tail > bestSize) {
                bestSize = tail;
                best = id;
            }
        }
        if (best == 0) {
            return;
        }
        // distance (in 4-neighbour steps) from the largest region, up to grow; -1 = farther
        int[] depth = new int[n];
        Arrays.fill(depth, -1);
        int head = 0;
        int tail = 0;
        for (int p = 0; p < n; p++) {
            if (label[p] == best) {
                depth[p] = 0;
                queue[tail++] = p;
            }
        }
        while (head < tail) {
            int p = queue[head++];
            if (depth[p] >= grow) {
                continue;
            }
            int x = p % w;
            int y = p / w;
            int[] around = {x > 0 ? p - 1 : -1, x < w - 1 ? p + 1 : -1, y > 0 ? p - w : -1, y < h - 1 ? p + w : -1};
            for (int q : around) {
                if (q >= 0 && depth[q] < 0) {
                    depth[q] = depth[p] + 1;
                    queue[tail++] = q;
                }
            }
        }
        for (int p = 0; p < n; p++) {
            if (depth[p] < 0 || (label[p] != 0 && label[p] != best)) {
                alpha[p] = 0f;
            }
        }
    }

    private static int visit(float[] alpha, int[] label, int[] queue, int tail, int q, int id) {
        if (alpha[q] >= 0.5f && label[q] == 0) {
            label[q] = id;
            queue[tail++] = q;
        }
        return tail;
    }

    private static Result fallback(String reason, String detail, Detection animal, Box crop, long detectMs,
                                   long segmentMs, long start) {
        return new Result("FALLBACK:" + reason, null, animal, crop, -1, detectMs, segmentMs, ms(start), detail);
    }

    private static long ms(long startNanos) {
        return Math.max(0, (System.nanoTime() - startNanos) / 1_000_000);
    }
}
