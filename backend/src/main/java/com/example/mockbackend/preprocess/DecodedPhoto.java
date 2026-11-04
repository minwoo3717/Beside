package com.example.mockbackend.preprocess;

import java.awt.image.BufferedImage;

/**
 * A photo decoded for the check or the cutout.
 *
 * @param image       TYPE_3BYTE_BGR, turned upright by EXIF, transparency flattened on white (as Python's load_rgb),
 *                    long side at most the requested target (never enlarged)
 * @param width       upright width of the original photo in pixels
 * @param height      upright height of the original photo in pixels
 * @param scale       original pixels per pixel of {@code image} (1 when not reduced)
 * @param orientation the EXIF orientation that was applied (1 = as stored)
 * @param format      reader format in lower case: jpeg, png, webp, ...
 * @param decodeMs    time to read, reduce and turn the photo
 */
public record DecodedPhoto(BufferedImage image, int width, int height, double scale, int orientation, String format,
                           long decodeMs) {
}
