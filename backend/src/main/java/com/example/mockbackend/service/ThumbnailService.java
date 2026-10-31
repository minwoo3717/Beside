package com.example.mockbackend.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Iterator;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Thumbnails of jobs for the web library: the first photo as a JPEG whose longer side is {@value #MAX_SIDE} px (smaller
 * photos keep their size), turned by its EXIF orientation, transparency on white, at {storage.thumb.dir}/{jobId}.jpg.
 * Queued when the job is accepted (JobServiceImpl.createLater) and made on one background thread, so the upload answer
 * never waits for a decode (a 12 MP photo takes hundreds of ms) and a burst of uploads queues instead of dropping
 * thumbnails; until it exists JobResponse.thumbnailUrl is null ("없으면 null" in openapi.yaml). Photos never change
 * afterwards, retry keeps it.
 * Reads what ImageIO reads plus WEBP (TwelveMonkeys imageio-webp registers itself as an ImageIO plugin).
 * A thumbnail is a convenience: anything that goes wrong is logged and the job is made without one.
 */
@Service
public class ThumbnailService {
    public static final int MAX_SIDE = 256;
    /** Bigger pictures are not decoded at all (a few kB of PNG can claim a huge canvas). 64 MP covers phone cameras. */
    static final long MAX_PIXELS = 64L * 1024 * 1024;
    /**
     * WEBP (TwelveMonkeys) decodes the full canvas before subsampling, so its memory follows the pixel count: a ~100 kB
     * lossless 64 MP file would cost ~200 MB per decode on the public upload path. 16 MP (~64 MB) is still more than
     * phones save as WEBP; JPEG and PNG readers subsample row by row and keep MAX_PIXELS.
     */
    static final long MAX_WEBP_PIXELS = 16L * 1024 * 1024;
    /** One decode at a time, server-wide: a burst of uploads cannot stack full-canvas rasters on the heap. */
    private static final Semaphore DECODES = new Semaphore(1);
    private static final long DECODE_WAIT_MS = 5_000;
    private static final float JPEG_QUALITY = 0.85f;

    private final Path dir;
    /** One thread: decodes run one at a time anyway (DECODES), and the queue keeps every upload's thumbnail. */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "thumbnails");
        thread.setDaemon(true);
        return thread;
    });
    private final Logger log = LoggerFactory.getLogger(ThumbnailService.class);

    public ThumbnailService(@Value("${storage.thumb.dir:storage/thumbs}") String dir) {
        this.dir = Path.of(dir);
    }

    @PostConstruct
    void init() throws IOException {
        Files.createDirectories(dir);
        // Plugins on the class path (imageio-webp) register when ImageIO is first used; scan again explicitly so that
        // the order of first use (and the class loader of that thread) does not matter.
        ImageIO.scanForPlugins();
    }

    @PreDestroy
    void stop() {
        worker.shutdownNow();
    }

    /**
     * Queues the thumbnail of {@code jobId} and returns at once. Never throws. When the job was deleted while its
     * thumbnail was being made (the photo is gone afterwards), the late thumbnail is removed again.
     */
    public void createLater(String jobId, Path photo) {
        try {
            worker.execute(() -> {
                if (create(jobId, photo) && !Files.exists(photo)) {
                    delete(jobId);
                }
            });
        } catch (RejectedExecutionException shuttingDown) {
            log.info("jobId={} no thumbnail: the server is shutting down", jobId);
        }
    }

    /** Waits until every thumbnail queued before this call is done (tests). False when the time ran out. */
    public boolean drain(Duration timeout) throws InterruptedException {
        Future<?> marker;
        try {
            marker = worker.submit(() -> { });
        } catch (RejectedExecutionException shuttingDown) {
            return false;
        }
        try {
            marker.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (ExecutionException | TimeoutException e) {
            return false;
        }
    }

    /** Makes the thumbnail of {@code jobId} from {@code photo} now. Never throws; false when it could not. */
    public boolean create(String jobId, Path photo) {
        long start = System.nanoTime();
        try {
            if (!DECODES.tryAcquire(DECODE_WAIT_MS, TimeUnit.MILLISECONDS)) {
                log.warn("jobId={} no thumbnail: another thumbnail was still decoding after {} ms", jobId, DECODE_WAIT_MS);
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        try {
            BufferedImage source = decode(photo);
            if (source == null) {
                log.info("jobId={} no thumbnail: the first photo is not an image this server can read", jobId);
                return false;
            }
            BufferedImage thumb = orient(scale(source), ExifOrientation.read(photo));
            write(thumb, file(jobId));
            log.info("jobId={} thumbnail {}x{} in {} ms", jobId, thumb.getWidth(), thumb.getHeight(),
                    (System.nanoTime() - start) / 1_000_000);
            return true;
        } catch (Exception | OutOfMemoryError e) {
            log.warn("jobId={} no thumbnail: {}", jobId, e.toString());
            return false;
        } finally {
            DECODES.release();
        }
    }

    /** The thumbnail file of a job, if it has one. */
    public Optional<Path> find(String jobId) {
        Path file = file(jobId);
        return Files.isRegularFile(file) ? Optional.of(file) : Optional.empty();
    }

    public boolean exists(String jobId) {
        return find(jobId).isPresent();
    }

    /** Deletes the thumbnail of a job (deleting the job). Never throws. */
    public void delete(String jobId) {
        try {
            Files.deleteIfExists(file(jobId));
        } catch (IOException | RuntimeException e) {
            log.warn("jobId={} thumbnail not deleted: {}", jobId, e.toString());
        }
    }

    private Path file(String jobId) {
        return dir.resolve(jobId + ".jpg");
    }

    /** Decodes the first image, subsampled while reading when it is much bigger than needed. Null if unreadable. */
    private static BufferedImage decode(Path photo) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(photo.toFile())) {
            if (in == null) {
                return null;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                long limit = "webp".equalsIgnoreCase(reader.getFormatName()) ? MAX_WEBP_PIXELS : MAX_PIXELS;
                if (width <= 0 || height <= 0 || (long) width * height > limit) {
                    throw new IOException("unsupported picture size " + width + "x" + height);
                }
                ImageReadParam param = reader.getDefaultReadParam();
                int step = Math.max(1, Math.max(width, height) / (2 * MAX_SIDE));
                if (step > 1) {
                    param.setSourceSubsampling(step, step, 0, 0);
                }
                return reader.read(0, param);
            } finally {
                reader.dispose();
            }
        }
    }

    /** Longer side down to MAX_SIDE in halving steps (smooth without a resampling library), on white, as RGB. */
    static BufferedImage scale(BufferedImage source) {
        int width = source.getWidth();
        int height = source.getHeight();
        double factor = Math.min(1.0, (double) MAX_SIDE / Math.max(width, height));
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

    private static BufferedImage draw(BufferedImage source, int width, int height) {
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
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
     * Applies EXIF orientation 2..8 (1 = as stored). 5..8 swap width and height. Pixel copy: the image is at most
     * 256 x 256 here.
     */
    static BufferedImage orient(BufferedImage image, int orientation) {
        if (orientation < 2 || orientation > 8) {
            return image;
        }
        int w = image.getWidth();
        int h = image.getHeight();
        boolean swap = orientation >= 5;
        BufferedImage out = new BufferedImage(swap ? h : w, swap ? w : h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = image.getRGB(x, y);
                switch (orientation) {
                    case 2 -> out.setRGB(w - 1 - x, y, rgb);          // mirrored
                    case 3 -> out.setRGB(w - 1 - x, h - 1 - y, rgb);  // 180
                    case 4 -> out.setRGB(x, h - 1 - y, rgb);          // mirrored upside down
                    case 5 -> out.setRGB(y, x, rgb);                  // transpose
                    case 6 -> out.setRGB(h - 1 - y, x, rgb);          // 90 clockwise
                    case 7 -> out.setRGB(h - 1 - y, w - 1 - x, rgb);  // transverse
                    default -> out.setRGB(y, w - 1 - x, rgb);         // 8: 90 counterclockwise
                }
            }
        }
        return out;
    }

    /** JPEG through a temporary file and a move, so a reader never sees half a file. */
    private static void write(BufferedImage image, Path target) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IOException("no JPEG writer");
        }
        ImageWriter writer = writers.next();
        Path temp = Files.createTempFile(target.getParent(), "thumb-", ".tmp");
        try {
            try (ImageOutputStream out = ImageIO.createImageOutputStream(temp.toFile())) {
                ImageWriteParam param = writer.getDefaultWriteParam();
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(JPEG_QUALITY);
                writer.setOutput(out);
                writer.write(null, new IIOImage(image, null, null), param);
            } finally {
                writer.dispose();
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
