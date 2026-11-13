package com.example.mockbackend.preprocess;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntSupplier;

/**
 * Decodes one photo for the check and the cutout: header first (refuses decoding bombs before allocating pixels),
 * subsampled while reading when it is much bigger than needed, reduced to a target long side in halving steps,
 * flattened on white, turned upright by EXIF, as TYPE_3BYTE_BGR (the channel order YOLOX reads).
 * <p>
 * Same safety rules as the review branch's thumbnails (header-first pixel cap, 64 MP / 16 MP for WEBP, a watchdog that
 * aborts a slow decode). Bytes are read through a memory cache, so ImageIO writes no temporary file.
 */
public final class ImageDecoding {

    /**
     * @param maxPixels     largest width x height read at all (a 100 kB WEBP can declare 64 MP)
     * @param maxWebpPixels the same for WEBP, whose plugin needs about 3 bytes per pixel more than JPEG
     * @param timeLimitMs   a decode running longer is aborted (progressive JPEG with many scans, broken streams)
     */
    public record Limits(long maxPixels, long maxWebpPixels, long timeLimitMs) {
        public static final Limits DEFAULT = new Limits(64L * 1024 * 1024, 16L * 1024 * 1024, 5000);
    }

    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "image-decode-watchdog");
        thread.setDaemon(true);
        return thread;
    });

    static {
        // Plugins on the class path (imageio-webp) register when ImageIO is first used; scan explicitly so that the
        // class loader of the first caller does not matter.
        ImageIO.scanForPlugins();
    }

    private ImageDecoding() {
    }

    /** Decodes the bytes of an uploaded photo. */
    public static DecodedPhoto decode(byte[] bytes, int targetLongSide, Limits limits) throws UnreadableImageException {
        long start = System.nanoTime();
        try (ImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            return decode(in, () -> ExifOrientation.fromJpeg(bytes), targetLongSide, limits, start);
        } catch (IOException e) {
            throw new UnreadableImageException("cannot read the photo: " + e.getMessage(), e);
        }
    }

    /** Decodes a stored photo. */
    public static DecodedPhoto decode(Path file, int targetLongSide, Limits limits) throws UnreadableImageException {
        long start = System.nanoTime();
        try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            if (in == null) {
                throw new UnreadableImageException("cannot open the stored photo");
            }
            return decode(in, () -> ExifOrientation.read(file), targetLongSide, limits, start);
        } catch (IOException e) {
            throw new UnreadableImageException("cannot read the stored photo: " + e.getMessage(), e);
        }
    }

    private static DecodedPhoto decode(ImageInputStream in, IntSupplier exifOrientation, int targetLongSide,
                                       Limits limits, long start) throws UnreadableImageException {
        if (targetLongSide < 1) {
            throw new IllegalArgumentException("targetLongSide must be positive");
        }
        Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
        if (!readers.hasNext()) {
            throw new UnreadableImageException("not an image format this server reads");
        }
        ImageReader reader = readers.next();
        try {
            reader.setInput(in, true, true);
            String format = reader.getFormatName().toLowerCase(Locale.ROOT);
            int width = reader.getWidth(0);
            int height = reader.getHeight(0);
            long limit = "webp".equals(format) ? limits.maxWebpPixels() : limits.maxPixels();
            if (width <= 0 || height <= 0 || (long) width * height > limit) {
                throw new UnreadableImageException("unsupported picture size " + width + "x" + height + " (" + format
                        + ", at most " + limit + " pixels)");
            }
            ImageReadParam param = reader.getDefaultReadParam();
            int step = Math.max(1, Math.max(width, height) / (2 * targetLongSide));
            if (step > 1) {
                param.setSourceSubsampling(step, step, 0, 0);
            }
            BufferedImage raw = readWithin(reader, param, limits.timeLimitMs());
            int orientation = "jpeg".equals(format) ? exifOrientation.getAsInt() : ExifOrientation.NORMAL;
            BufferedImage upright = orient(toBgr(raw, targetLongSide), orientation);
            boolean swap = orientation >= 5;
            int uprightWidth = swap ? height : width;
            int uprightHeight = swap ? width : height;
            double scale = (double) Math.max(uprightWidth, uprightHeight)
                    / Math.max(upright.getWidth(), upright.getHeight());
            return new DecodedPhoto(upright, uprightWidth, uprightHeight, scale, orientation, format,
                    (System.nanoTime() - start) / 1_000_000);
        } catch (UnreadableImageException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            // ImageIO plugins report broken data with all kinds of runtime exceptions.
            throw new UnreadableImageException("cannot decode the photo: " + e, e);
        } finally {
            reader.dispose();
        }
    }

    private static BufferedImage readWithin(ImageReader reader, ImageReadParam param, long limitMs)
            throws IOException, UnreadableImageException {
        AtomicBoolean timedOut = new AtomicBoolean();
        ScheduledFuture<?> guard = WATCHDOG.schedule(() -> {
            timedOut.set(true);
            reader.abort();
        }, limitMs, TimeUnit.MILLISECONDS);
        try {
            BufferedImage image = reader.read(0, param);
            if (timedOut.get() || image == null) {
                throw new UnreadableImageException("decode took longer than " + limitMs + " ms (aborted)");
            }
            return image;
        } finally {
            guard.cancel(false);
        }
    }

    /** Long side down to {@code targetLongSide} (never up) in halving steps, on white, as TYPE_3BYTE_BGR. */
    static BufferedImage toBgr(BufferedImage source, int targetLongSide) {
        int width = source.getWidth();
        int height = source.getHeight();
        double factor = Math.min(1.0, (double) targetLongSide / Math.max(width, height));
        int targetWidth = Math.max(1, (int) Math.round(width * factor));
        int targetHeight = Math.max(1, (int) Math.round(height * factor));

        BufferedImage current = source;
        int w = width;
        int h = height;
        while (w / 2 >= targetWidth && h / 2 >= targetHeight && (w > targetWidth * 2 || h > targetHeight * 2)) {
            w /= 2;
            h /= 2;
            current = draw(current, w, h);
        }
        return draw(current, targetWidth, targetHeight);
    }

    static BufferedImage draw(BufferedImage source, int width, int height) {
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = target.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(source, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        return target;
    }

    /**
     * Applies EXIF orientation 2..8 (1 = as stored) to a TYPE_3BYTE_BGR image by copying its bytes; 5..8 swap width
     * and height. Mapping as PIL's ImageOps.exif_transpose.
     */
    static BufferedImage orient(BufferedImage image, int orientation) {
        if (orientation < 2 || orientation > 8) {
            return image;
        }
        int w = image.getWidth();
        int h = image.getHeight();
        boolean swap = orientation >= 5;
        int nw = swap ? h : w;
        BufferedImage out = new BufferedImage(nw, swap ? w : h, BufferedImage.TYPE_3BYTE_BGR);
        byte[] src = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
        byte[] dst = ((DataBufferByte) out.getRaster().getDataBuffer()).getData();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int nx;
                int ny;
                switch (orientation) {
                    case 2 -> { nx = w - 1 - x; ny = y; }              // mirrored
                    case 3 -> { nx = w - 1 - x; ny = h - 1 - y; }      // 180
                    case 4 -> { nx = x; ny = h - 1 - y; }              // mirrored upside down
                    case 5 -> { nx = y; ny = x; }                      // transpose
                    case 6 -> { nx = h - 1 - y; ny = x; }              // 90 clockwise
                    case 7 -> { nx = h - 1 - y; ny = w - 1 - x; }      // transverse
                    default -> { nx = y; ny = w - 1 - x; }             // 8: 90 counterclockwise
                }
                int s = (y * w + x) * 3;
                int d = (ny * nw + nx) * 3;
                dst[d] = src[s];
                dst[d + 1] = src[s + 1];
                dst[d + 2] = src[s + 2];
            }
        }
        return out;
    }
}
