package com.example.mockbackend.preprocess;

import java.awt.image.BufferedImage;

/**
 * Separates the animal from the background of a crop around it. Tests pass lambdas; the server uses
 * {@code OnnxSaliencySegmenter} (U2Net by default).
 */
@FunctionalInterface
public interface ForegroundSegmenter {

    /**
     * @param crop TYPE_3BYTE_BGR crop around the animal
     * @return alpha 0..1 for every pixel of {@code crop}, row by row; null when the model sees no foreground at all
     */
    float[] segment(BufferedImage crop) throws PreprocessException;

    /** Short description for logs and the preprocessVersion string. */
    default String name() {
        return "segmenter";
    }
}
