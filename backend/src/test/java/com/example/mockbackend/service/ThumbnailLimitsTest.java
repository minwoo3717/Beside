package com.example.mockbackend.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The limits around the thumbnail thread: a decode that runs past its time is aborted (a crafted progressive JPEG
 * must not hold the thread), and thumbnails still queued at shutdown are counted in the log.
 */
@ExtendWith(OutputCaptureExtension.class)
class ThumbnailLimitsTest {

    @TempDir
    Path folder;

    @Test
    void aDecodeThatRunsPastItsLimitIsAbortedAndGivesNoThumbnail() throws Exception {
        Path photo = progressiveJpeg(folder.resolve("big.jpg"), 2048, 2048);
        ThumbnailService slowAllowed = service("slow", 60_000, 64);
        assertThat(slowAllowed.create("job-1", photo)).isTrue();
        assertThat(slowAllowed.exists("job-1")).isTrue();

        ThumbnailService strict = service("strict", 1, 64); // 1 ms: a 2048 x 2048 progressive decode takes longer
        long started = System.nanoTime();
        assertThat(strict.create("job-2", photo)).isFalse();
        assertThat(strict.exists("job-2")).isFalse();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(30));
        slowAllowed.stop();
        strict.stop();
    }

    @Test
    void thumbnailsStillQueuedAtShutdownAreCounted(CapturedOutput output) throws Exception {
        Path photo = progressiveJpeg(folder.resolve("queued.jpg"), 2048, 2048);
        ThumbnailService service = service("queue", 60_000, 64);
        for (int i = 0; i < 6; i++) {
            service.createLater("job-" + i, photo); // each decode takes tens of ms: most are still waiting below
        }
        service.stop();
        assertThat(output.getOut()).containsPattern("[1-6] queued thumbnails dropped at shutdown");
    }

    private ThumbnailService service(String name, long decodeLimitMs, int queueLimit) throws IOException {
        ThumbnailService service = new ThumbnailService(folder.resolve(name).toString(), decodeLimitMs, queueLimit);
        service.init();
        return service;
    }

    /** A noisy progressive JPEG (several scans), so decoding it takes real time. */
    private static Path progressiveJpeg(Path file, int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        java.util.Random random = new java.util.Random(7);
        for (int y = 0; y < height; y += 16) {
            for (int x = 0; x < width; x += 16) {
                g.setColor(new Color(random.nextInt(0xFFFFFF)));
                g.fillRect(x, y, 16, 16);
            }
        }
        g.dispose();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(file.toFile())) {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setProgressiveMode(ImageWriteParam.MODE_DEFAULT);
            writer.setOutput(out);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        assertThat(Files.size(file)).isGreaterThan(10_000);
        return file;
    }
}
