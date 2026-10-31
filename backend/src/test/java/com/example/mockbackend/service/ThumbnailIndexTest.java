package com.example.mockbackend.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * exists() answers from memory: JobResponse.thumbnailUrl asks it for every response and every list item, and one file
 * check costs about 0.1 ms on Windows with real-time antivirus. The set follows create and delete, is filled from the
 * folder at start, and find(), which serves the file, corrects it when a file went missing behind the server's back.
 */
class ThumbnailIndexTest {

    @TempDir
    Path folder;

    @Test
    void existsFollowsCreateAndDelete() throws Exception {
        ThumbnailService service = service();
        Path photo = png(folder.resolve("photo.png"));
        assertThat(service.exists("job-1")).isFalse();

        assertThat(service.create("job-1", photo)).isTrue();
        assertThat(service.exists("job-1")).isTrue();
        assertThat(folder.resolve("thumbs").resolve("job-1.jpg")).isRegularFile();

        service.delete("job-1");
        assertThat(service.exists("job-1")).isFalse();
        assertThat(folder.resolve("thumbs").resolve("job-1.jpg")).doesNotExist();
        service.stop();
    }

    @Test
    void thumbnailsAlreadyOnDiskAreKnownAfterStart() throws Exception {
        Path dir = Files.createDirectories(folder.resolve("thumbs"));
        Files.write(dir.resolve("made-before-restart.jpg"), new byte[]{1});
        Files.write(dir.resolve("thumb-123.tmp"), new byte[]{1}); // a temp file left by a crash is not a thumbnail

        ThumbnailService service = service();
        assertThat(service.exists("made-before-restart")).isTrue();
        assertThat(service.exists("thumb-123")).isFalse();
        service.stop();
    }

    @Test
    void aFileRemovedBehindTheServersBackIsForgottenWhenItIsServed() throws Exception {
        ThumbnailService service = service();
        assertThat(service.create("job-2", png(folder.resolve("p.png")))).isTrue();
        Files.delete(folder.resolve("thumbs").resolve("job-2.jpg"));

        assertThat(service.exists("job-2")).as("no file check per response").isTrue();
        assertThat(service.find("job-2")).isEmpty();
        assertThat(service.exists("job-2")).isFalse();
        service.stop();
    }

    private ThumbnailService service() throws IOException {
        ThumbnailService service = new ThumbnailService(folder.resolve("thumbs").toString(), 10_000, 64);
        service.init();
        return service;
    }

    private static Path png(Path file) throws IOException {
        ImageIO.write(new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB), "png", file.toFile());
        return file;
    }
}
