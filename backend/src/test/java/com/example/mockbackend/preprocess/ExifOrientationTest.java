package com.example.mockbackend.preprocess;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class ExifOrientationTest {

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8})
    void readsEveryOrientationInBothByteOrders(int orientation) {
        assertThat(ExifOrientation.fromJpeg(Pictures.jpegWithOrientation(40, 20, orientation, ByteOrder.BIG_ENDIAN)))
                .isEqualTo(orientation);
        assertThat(ExifOrientation.fromJpeg(Pictures.jpegWithOrientation(40, 20, orientation, ByteOrder.LITTLE_ENDIAN)))
                .isEqualTo(orientation);
    }

    @Test
    void readsFromAFile(@TempDir Path dir) throws IOException {
        Path photo = Files.write(dir.resolve("사진.jpg"), Pictures.jpegWithOrientation(40, 20, 6));
        assertThat(ExifOrientation.read(photo)).isEqualTo(6);
        assertThat(ExifOrientation.read(dir.resolve("missing.jpg"))).isEqualTo(ExifOrientation.NORMAL);
    }

    @Test
    void anythingElseIsAsStored() {
        byte[] withTag = Pictures.jpegWithOrientation(40, 20, 6);
        assertThat(ExifOrientation.fromJpeg(Pictures.jpeg(40, 20))).as("no EXIF").isEqualTo(1);
        assertThat(ExifOrientation.fromJpeg(Pictures.pngWithAlpha(40, 20))).as("PNG").isEqualTo(1);
        assertThat(ExifOrientation.fromJpeg(new byte[0])).as("empty").isEqualTo(1);
        assertThat(ExifOrientation.fromJpeg("not a picture".getBytes())).as("text").isEqualTo(1);
        assertThat(ExifOrientation.fromJpeg(Arrays.copyOf(withTag, 12))).as("cut inside APP1").isEqualTo(1);
        assertThat(ExifOrientation.fromJpeg(Pictures.jpegWithOrientation(40, 20, 9))).as("out of range").isEqualTo(1);
        assertThat(ExifOrientation.fromJpeg(Pictures.jpegWithOrientation(40, 20, 0))).as("zero").isEqualTo(1);
    }
}
