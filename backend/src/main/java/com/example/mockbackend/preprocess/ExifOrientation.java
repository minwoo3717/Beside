package com.example.mockbackend.preprocess;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * EXIF Orientation (tag 0x0112 of IFD0) of a JPEG, which ImageIO ignores: phone cameras store the picture as the sensor
 * saw it and say in this tag how to turn it. Reads only the first APP1 "Exif" segment before the image data and never
 * throws; anything unexpected is orientation 1 (as stored).
 * <p>
 * Same parser as the thumbnail code of the review branch (review/api-v1.1, service/ExifOrientation); when the two
 * meet, the thumbnails use this public copy. The Python inference service applies the same tag with
 * {@code ImageOps.exif_transpose}, so a photo this server turns upright must be written without the tag.
 */
public final class ExifOrientation {
    public static final int NORMAL = 1;
    /** JPEG markers sit in the first segments; do not read further than this into a file. */
    static final int MAX_HEADER_BYTES = 256 * 1024;

    private ExifOrientation() {
    }

    /** 1..8 as defined by EXIF, 1 when the file is not a JPEG or carries no (valid) orientation. */
    public static int read(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            return fromJpeg(in.readNBytes(MAX_HEADER_BYTES));
        } catch (IOException | RuntimeException e) {
            return NORMAL;
        }
    }

    /** Same as {@link #read(Path)} for the bytes of a whole file (or at least its first segments). */
    public static int fromJpeg(byte[] bytes) {
        try {
            return parse(bytes);
        } catch (RuntimeException e) {
            return NORMAL;
        }
    }

    private static int parse(byte[] bytes) {
        ByteBuffer jpeg = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        if (jpeg.remaining() < 4 || (jpeg.get() & 0xFF) != 0xFF || (jpeg.get() & 0xFF) != 0xD8) {
            return NORMAL;
        }
        while (jpeg.remaining() >= 4) {
            if ((jpeg.get() & 0xFF) != 0xFF) {
                return NORMAL;
            }
            int marker = jpeg.get() & 0xFF;
            if (marker == 0xD8 || (marker >= 0xD0 && marker <= 0xD7) || marker == 0x01 || marker == 0xFF) {
                continue; // markers without a length
            }
            if (marker == 0xDA || marker == 0xD9) {
                return NORMAL; // image data starts: no EXIF before it
            }
            int length = jpeg.getShort() & 0xFFFF;
            if (length < 2 || length - 2 > jpeg.remaining()) {
                return NORMAL;
            }
            int start = jpeg.position();
            if (marker == 0xE1 && length >= 8 + 6 && isExifHeader(jpeg, start)) {
                return fromTiff(ByteBuffer.wrap(bytes, start + 6, length - 2 - 6).slice());
            }
            jpeg.position(start + length - 2);
        }
        return NORMAL;
    }

    private static boolean isExifHeader(ByteBuffer jpeg, int at) {
        return jpeg.get(at) == 'E' && jpeg.get(at + 1) == 'x' && jpeg.get(at + 2) == 'i' && jpeg.get(at + 3) == 'f'
                && jpeg.get(at + 4) == 0 && jpeg.get(at + 5) == 0;
    }

    /** TIFF header ("II" or "MM", 42, IFD0 offset), then the IFD0 entries. */
    private static int fromTiff(ByteBuffer tiff) {
        if (tiff.remaining() < 8) {
            return NORMAL;
        }
        if (tiff.get(0) == 'I' && tiff.get(1) == 'I') {
            tiff.order(ByteOrder.LITTLE_ENDIAN);
        } else if (tiff.get(0) == 'M' && tiff.get(1) == 'M') {
            tiff.order(ByteOrder.BIG_ENDIAN);
        } else {
            return NORMAL;
        }
        if ((tiff.getShort(2) & 0xFFFF) != 42) {
            return NORMAL;
        }
        long ifd = tiff.getInt(4) & 0xFFFFFFFFL;
        if (ifd < 8 || ifd + 2 > tiff.limit()) {
            return NORMAL;
        }
        int entries = tiff.getShort((int) ifd) & 0xFFFF;
        for (int i = 0; i < entries; i++) {
            int entry = (int) ifd + 2 + i * 12;
            if (entry + 12 > tiff.limit()) {
                return NORMAL;
            }
            int tag = tiff.getShort(entry) & 0xFFFF;
            int type = tiff.getShort(entry + 2) & 0xFFFF;
            if (tag == 0x0112 && type == 3) { // SHORT, value stored in the entry itself
                int value = tiff.getShort(entry + 8) & 0xFFFF;
                return value >= 1 && value <= 8 ? value : NORMAL;
            }
        }
        return NORMAL;
    }
}
