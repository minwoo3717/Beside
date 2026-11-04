package com.example.mockbackend.preprocess;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Small real images for the preprocessing tests, drawn here and encoded with ImageIO (no binary fixtures except WEBP,
 * which ImageIO cannot write: src/test/resources/images/halves-300x200.webp, the same file as the review branch's).
 * The "halves" images are red on the left and blue on the right, so a test can tell how a picture was turned.
 */
final class Pictures {
    static final Color LEFT = new Color(220, 30, 30);
    static final Color RIGHT = new Color(30, 30, 220);

    private Pictures() {
    }

    static BufferedImage halves(int width, int height, int type) {
        BufferedImage image = new BufferedImage(width, height, type);
        Graphics2D g = image.createGraphics();
        g.setColor(LEFT);
        g.fillRect(0, 0, width / 2, height);
        g.setColor(RIGHT);
        g.fillRect(width / 2, 0, width - width / 2, height);
        g.dispose();
        return image;
    }

    static byte[] jpeg(int width, int height) {
        return encode(halves(width, height, BufferedImage.TYPE_INT_RGB), "jpeg");
    }

    /** PNG with an alpha channel: the right half is fully transparent. */
    static byte[] pngWithAlpha(int width, int height) {
        BufferedImage image = halves(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = width / 2; x < width; x++) {
                image.setRGB(x, y, 0x00000000);
            }
        }
        return encode(image, "png");
    }

    /** A JPEG whose EXIF APP1 (right after SOI) holds only Orientation = {@code orientation}, big-endian ("MM"). */
    static byte[] jpegWithOrientation(int width, int height, int orientation) {
        return jpegWithOrientation(width, height, orientation, ByteOrder.BIG_ENDIAN);
    }

    /** Same, with the TIFF byte order of the EXIF block ("II" little-endian or "MM" big-endian). */
    static byte[] jpegWithOrientation(int width, int height, int orientation, ByteOrder order) {
        byte[] plain = jpeg(width, height);
        ByteBuffer tiff = ByteBuffer.allocate(26).order(order); // header 8 + count 2 + one entry 12 + next IFD 4
        boolean little = order == ByteOrder.LITTLE_ENDIAN;
        tiff.put(little ? (byte) 'I' : (byte) 'M').put(little ? (byte) 'I' : (byte) 'M').putShort((short) 42).putInt(8)
                .putShort((short) 1)
                .putShort((short) 0x0112).putShort((short) 3).putInt(1).putShort((short) orientation).putShort((short) 0)
                .putInt(0);
        byte[] exifHeader = {'E', 'x', 'i', 'f', 0, 0};
        int length = 2 + exifHeader.length + tiff.capacity();
        ByteBuffer out = ByteBuffer.allocate(plain.length + 2 + length);
        out.put(plain, 0, 2) // SOI
                .put((byte) 0xFF).put((byte) 0xE1).putShort((short) length).put(exifHeader).put(tiff.array())
                .put(plain, 2, plain.length - 2);
        return out.array();
    }

    /** A real WEBP (lossy, 300 x 200, left half red, right half blue) made by Chrome's encoder. */
    static byte[] webp() {
        try (InputStream in = Pictures.class.getResourceAsStream("/images/halves-300x200.webp")) {
            if (in == null) {
                throw new IllegalStateException("missing test resource /images/halves-300x200.webp");
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Whether the pixel is closer to {@code expected} than to the other half's color (JPEG blurs exact values). */
    static boolean near(BufferedImage image, int x, int y, Color expected) {
        Color actual = new Color(image.getRGB(x, y));
        Color other = expected.equals(LEFT) ? RIGHT : LEFT;
        return distance(actual, expected) < distance(actual, other);
    }

    static boolean isWhite(BufferedImage image, int x, int y) {
        Color c = new Color(image.getRGB(x, y));
        return c.getRed() > 235 && c.getGreen() > 235 && c.getBlue() > 235;
    }

    static byte[] encode(BufferedImage image, String format) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(image, format, out)) {
                throw new IllegalStateException("no ImageIO writer for " + format);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private static int distance(Color a, Color b) {
        int r = a.getRed() - b.getRed();
        int g = a.getGreen() - b.getGreen();
        int bl = a.getBlue() - b.getBlue();
        return r * r + g * g + bl * bl;
    }
}
