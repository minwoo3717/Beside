package com.example.mockbackend.preprocess;

import java.awt.image.BufferedImage;
import java.util.List;

/**
 * Finds the animals the service accepts (dogs and cats) in an upright photo. Tests pass lambdas; the server uses
 * {@code YoloxDetector}.
 */
@FunctionalInterface
public interface AnimalDetector {

    /**
     * @param uprightBgr a decoded photo ({@link DecodedPhoto#image()}, TYPE_3BYTE_BGR)
     * @return the animals after suppression, best score first, boxes in the pixels of {@code uprightBgr}
     */
    List<Detection> detect(BufferedImage uprightBgr) throws PreprocessException;

    /** Short description for logs and the preprocessVersion string. */
    default String name() {
        return "detector";
    }

    /**
     * @param box     where, in the pixels of the picture that was searched
     * @param classId COCO 80-class index (15 cat, 16 dog)
     * @param score   objectness x class probability, 0..1
     */
    record Detection(Box box, int classId, float score) {
    }
}
