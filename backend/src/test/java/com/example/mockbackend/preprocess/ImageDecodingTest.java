package com.example.mockbackend.preprocess;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.CRC32;

import static com.example.mockbackend.preprocess.Pictures.LEFT;
import static com.example.mockbackend.preprocess.Pictures.RIGHT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImageDecodingTest {
    private static final ImageDecoding.Limits LIMITS = ImageDecoding.Limits.DEFAULT;

    @Test
    void jpegAsStoredBecomesBgrOfTheSameSize() throws Exception {
        DecodedPhoto photo = ImageDecoding.decode(Pictures.jpeg(300, 200), 1600, LIMITS);

        assertThat(photo.image().getType()).isEqualTo(BufferedImage.TYPE_3BYTE_BGR);
        assertThat(photo.image().getWidth()).isEqualTo(300);
        assertThat(photo.image().getHeight()).isEqualTo(200);
        assertThat(photo.width()).isEqualTo(300);
        assertThat(photo.height()).isEqualTo(200);
        assertThat(photo.scale()).isEqualTo(1.0);
        assertThat(photo.orientation()).isEqualTo(1);
        assertThat(photo.format()).isEqualTo("jpeg");
        assertThat(Pictures.near(photo.image(), 30, 100, LEFT)).isTrue();
        assertThat(Pictures.near(photo.image(), 270, 100, RIGHT)).isTrue();
    }

    /** Red is stored on the left; after turning, it is where EXIF says the left edge belongs. */
    @ParameterizedTest(name = "orientation {0}")
    @CsvSource({
            // orientation, width, height, red probe x,y, blue probe x,y
            "2, 300, 200, 270, 100, 30, 100",   // mirrored
            "3, 300, 200, 270, 100, 30, 100",   // 180
            "4, 300, 200, 30, 100, 270, 100",   // upside down mirrored: left stays left
            "5, 200, 300, 100, 30, 100, 270",   // transpose: left edge to the top
            "6, 200, 300, 100, 30, 100, 270",   // 90 clockwise: left edge to the top
            "7, 200, 300, 100, 270, 100, 30",   // transverse: left edge to the bottom
            "8, 200, 300, 100, 270, 100, 30"})  // 90 counterclockwise: left edge to the bottom
    void turnsJpegUprightByExif(int orientation, int width, int height, int rx, int ry, int bx, int by) throws Exception {
        DecodedPhoto photo = ImageDecoding.decode(Pictures.jpegWithOrientation(300, 200, orientation), 1600, LIMITS);

        assertThat(photo.orientation()).isEqualTo(orientation);
        assertThat(photo.image().getWidth()).isEqualTo(width);
        assertThat(photo.image().getHeight()).isEqualTo(height);
        assertThat(photo.width()).isEqualTo(width);
        assertThat(photo.height()).isEqualTo(height);
        assertThat(Pictures.near(photo.image(), rx, ry, LEFT)).as("red at %d,%d", rx, ry).isTrue();
        assertThat(Pictures.near(photo.image(), bx, by, RIGHT)).as("blue at %d,%d", bx, by).isTrue();
    }

    @Test
    void transparencyIsFlattenedOnWhiteLikePython() throws Exception {
        DecodedPhoto photo = ImageDecoding.decode(Pictures.pngWithAlpha(300, 200), 1600, LIMITS);

        assertThat(photo.format()).isEqualTo("png");
        assertThat(Pictures.near(photo.image(), 30, 100, LEFT)).isTrue();
        assertThat(Pictures.isWhite(photo.image(), 270, 100)).isTrue();
    }

    @Test
    void readsWebp() throws Exception {
        DecodedPhoto photo = ImageDecoding.decode(Pictures.webp(), 1600, LIMITS);

        assertThat(photo.format()).isEqualTo("webp");
        assertThat(photo.image().getWidth()).isEqualTo(300);
        assertThat(photo.image().getHeight()).isEqualTo(200);
        assertThat(Pictures.near(photo.image(), 30, 100, LEFT)).isTrue();
        assertThat(Pictures.near(photo.image(), 270, 100, RIGHT)).isTrue();
    }

    @Test
    void bigPhotoComesDownToTheTargetLongSide() throws Exception {
        byte[] big = Pictures.encode(Pictures.halves(3200, 2400, BufferedImage.TYPE_INT_RGB), "jpeg");

        DecodedPhoto photo = ImageDecoding.decode(big, 1600, LIMITS);

        assertThat(photo.image().getWidth()).isEqualTo(1600);
        assertThat(photo.image().getHeight()).isEqualTo(1200);
        assertThat(photo.width()).isEqualTo(3200);
        assertThat(photo.height()).isEqualTo(2400);
        assertThat(photo.scale()).isEqualTo(2.0);
        assertThat(Pictures.near(photo.image(), 100, 600, LEFT)).isTrue();
        assertThat(Pictures.near(photo.image(), 1500, 600, RIGHT)).isTrue();
    }

    @Test
    void refusesTooManyPixelsFromTheHeaderAlone() {
        byte[] bomb = pngHeaderOnly(20000, 20000); // 400 MP declared, no pixel data at all

        assertThatThrownBy(() -> ImageDecoding.decode(bomb, 1600, LIMITS))
                .isInstanceOf(UnreadableImageException.class).hasMessageContaining("20000x20000");
    }

    @Test
    void webpHasItsOwnLowerLimit() throws Exception {
        ImageDecoding.Limits tight = new ImageDecoding.Limits(300 * 200, 300 * 200 - 1, 5000);

        assertThatThrownBy(() -> ImageDecoding.decode(Pictures.webp(), 1600, tight))
                .isInstanceOf(UnreadableImageException.class).hasMessageContaining("300x200");
        assertThat(ImageDecoding.decode(Pictures.jpeg(300, 200), 1600, tight).width()).isEqualTo(300);
    }

    @Test
    void anythingButAnImageIsUnreadable() {
        byte[] jpeg = Pictures.jpeg(300, 200);
        for (byte[] bytes : new byte[][]{new byte[0], "not a picture".getBytes(StandardCharsets.UTF_8),
                Arrays.copyOf(jpeg, 20), new byte[64]}) {
            assertThatThrownBy(() -> ImageDecoding.decode(bytes, 1600, LIMITS))
                    .as("%d bytes", bytes.length).isInstanceOf(UnreadableImageException.class);
        }
    }

    @Test
    void decodesAStoredFileAndItsExif(@TempDir Path dir) throws Exception {
        Path stored = Files.write(dir.resolve("우리 강아지.jpg"), Pictures.jpegWithOrientation(300, 200, 6));

        DecodedPhoto photo = ImageDecoding.decode(stored, 1600, LIMITS);

        assertThat(photo.orientation()).isEqualTo(6);
        assertThat(photo.image().getWidth()).isEqualTo(200);
        assertThat(photo.image().getHeight()).isEqualTo(300);
        assertThat(Pictures.near(photo.image(), 100, 30, LEFT)).isTrue();
        assertThatThrownBy(() -> ImageDecoding.decode(dir.resolve("missing.jpg"), 1600, LIMITS))
                .isInstanceOf(UnreadableImageException.class);
    }

    @Test
    void reportsTheDecodeTime() throws Exception {
        assertThat(ImageDecoding.decode(Pictures.jpeg(300, 200), 1600, LIMITS).decodeMs()).isNotNegative();
    }

    /** PNG signature + IHDR (8-bit RGB) + IEND: enough for a reader to report the size, no image data. */
    private static byte[] pngHeaderOnly(int width, int height) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
        chunk(out, "IHDR", ByteBuffer.allocate(13).putInt(width).putInt(height).put((byte) 8).put((byte) 2)
                .put((byte) 0).put((byte) 0).put((byte) 0).array());
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(data);
        out.writeBytes(ByteBuffer.allocate(4).putInt(data.length).array());
        out.writeBytes(name);
        out.writeBytes(data);
        out.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }
}
