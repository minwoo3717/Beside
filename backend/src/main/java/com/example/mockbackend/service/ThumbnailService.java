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
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thumbnails of jobs for the web library: the first photo as a JPEG whose longer side is {@value #MAX_SIDE} px (smaller
 * photos keep their size), turned by its EXIF orientation, transparency on white, at {storage.thumb.dir}/{jobId}.jpg.
 * Queued when the job is accepted (JobServiceImpl.createLater) and made on one background thread, so the upload answer
 * never waits for a decode (a 12 MP photo takes hundreds of ms) and a burst of uploads queues instead of dropping
 * thumbnails; until it exists JobResponse.thumbnailUrl is null ("없으면 null" in openapi.yaml). Photos never change
 * afterwards, retry keeps it. Whether a job has one is answered from memory (exists()), not from the disk.
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
     * phones save as WEBP. PNG and baseline JPEG readers subsample row by row and keep MAX_PIXELS; a progressive JPEG
     * keeps a whole-image coefficient buffer and makes one output pass per scan (a file may carry thousands), which is
     * why every decode also has a time limit (storage.thumb.decode-limit-ms).
     */
    static final long MAX_WEBP_PIXELS = 16L * 1024 * 1024;
    /** One decode at a time, server-wide: a burst of uploads cannot stack full-canvas rasters on the heap. */
    private static final Semaphore DECODES = new Semaphore(1);
    private static final long DECODE_WAIT_MS = 5_000;
    private static final float JPEG_QUALITY = 0.85f;
    private static final String SUFFIX = ".jpg";

    private final Path dir;
    private final long decodeLimitMs;
    /**
     * Job ids whose thumbnail file exists, so exists() (JobResponse.thumbnailUrl: every response, every list item) needs
     * no file check, which costs about 0.1 ms on Windows with real-time antivirus (measured on the dev PC: 97–106 µs per
     * check, 0.5 µs per set lookup). Filled from the folder at start and kept by create and delete; find(), which
     * serves the file, corrects it when a file went missing behind the server's back. One server per storage folder,
     * as for the DB file.
     */
    private final Set<String> known = ConcurrentHashMap.newKeySet();
    /**
     * One thread: decodes run one at a time anyway (DECODES). The queue keeps a burst of uploads' thumbnails up to
     * queueLimit; past that a new upload gets none (thumbnailUrl stays null) instead of growing the heap.
     */
    private final ThreadPoolExecutor worker;
    /** Aborts a decode that runs past decodeLimitMs (ImageReader.abort may be called from another thread). */
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(daemon("thumbnail-watchdog"));
    private final Logger log = LoggerFactory.getLogger(ThumbnailService.class);

    public ThumbnailService(@Value("${storage.thumb.dir:storage/thumbs}") String dir,
                            @Value("${storage.thumb.decode-limit-ms:10000}") long decodeLimitMs,
                            @Value("${storage.thumb.queue-limit:64}") int queueLimit) {
        this.dir = Path.of(dir);
        this.decodeLimitMs = decodeLimitMs;
        this.worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(queueLimit),
                daemon("thumbnails"));
    }

    private static ThreadFactory daemon(String name) {
        return task -> {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    @PostConstruct
    void init() throws IOException {
        Files.createDirectories(dir);
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*" + SUFFIX)) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                known.add(name.substring(0, name.length() - SUFFIX.length()));
            }
        }
        // Plugins on the class path (imageio-webp) register when ImageIO is first used; scan again explicitly so that
        // the order of first use (and the class loader of that thread) does not matter.
        ImageIO.scanForPlugins();
    }

    @PreDestroy
    void stop() {
        List<Runnable> dropped = worker.shutdownNow();
        watchdog.shutdownNow();
        if (!dropped.isEmpty()) {
            log.warn("{} queued thumbnails dropped at shutdown; a retry of those jobs makes them again", dropped.size());
        }
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
        } catch (RejectedExecutionException rejected) {
            if (worker.isShutdown()) {
                log.info("jobId={} no thumbnail: the server is shutting down", jobId);
            } else {
                log.warn("jobId={} no thumbnail: {} thumbnails are already waiting", jobId, worker.getQueue().size());
            }
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
            known.add(jobId); // after the move: a thumbnailUrl is never handed out before its file exists
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

    /** The thumbnail file of a job, if it has one (checked on disk: it is about to be served). */
    public Optional<Path> find(String jobId) {
        Path file = file(jobId);
        if (Files.isRegularFile(file)) {
            known.add(jobId);
            return Optional.of(file);
        }
        // Remove, then look once more: create() adds only after its move, so a thumbnail finished between the two
        // checks is either seen here or added again by create() after this remove.
        known.remove(jobId);
        if (Files.isRegularFile(file)) {
            known.add(jobId);
            return Optional.of(file);
        }
        return Optional.empty();
    }

    /** Whether the job has a thumbnail, from memory (see {@link #known}). */
    public boolean exists(String jobId) {
        return known.contains(jobId);
    }

    /** Deletes the thumbnail of a job (deleting the job). Never throws. */
    public void delete(String jobId) {
        known.remove(jobId);
        try {
            Files.deleteIfExists(file(jobId));
        } catch (IOException | RuntimeException e) {
            log.warn("jobId={} thumbnail not deleted: {}", jobId, e.toString());
        }
    }

    private Path file(String jobId) {
        return dir.resolve(jobId + SUFFIX);
    }

    /**
     * Decodes the first image, subsampled while reading when it is much bigger than needed. Null if unreadable.
     * @throws IOException also when the decode ran past decodeLimitMs (aborted)
     */
    private BufferedImage decode(Path photo) throws IOException {
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
                AtomicBoolean timedOut = new AtomicBoolean();
                ScheduledFuture<?> guard = watchdog.schedule(() -> {
                    timedOut.set(true);
                    reader.abort();
                }, decodeLimitMs, TimeUnit.MILLISECONDS);
                try {
                    BufferedImage image = reader.read(0, param);
                    if (timedOut.get()) {
                        throw new IOException("decode took longer than " + decodeLimitMs + " ms (aborted)");
                    }
                    return image;
                } finally {
                    guard.cancel(false);
                }
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
