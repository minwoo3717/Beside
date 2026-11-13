package com.example.mockbackend.preprocess;

import com.example.mockbackend.preprocess.AnimalDetector.Detection;

import java.util.List;
import java.util.Locale;

/**
 * The check of the first photo before the 202: readable, big enough, exactly one dog or cat, the animal big enough,
 * light enough and sharp enough, in that order. The first failing rule is the answer. A photo that fails is a
 * {@link Result} with a {@link Reason}; only a fault of the engine (the detector) is an exception.
 */
public final class PhotoCheck {

    /** Why a photo is refused, named exactly as the API error codes (ERROR_CODES.md "대표 사진 검사"). */
    public enum Reason {
        UNREADABLE_IMAGE, IMAGE_TOO_SMALL, NO_ANIMAL_DETECTED, TOO_MANY_ANIMALS, ANIMAL_TOO_SMALL, IMAGE_TOO_DARK,
        IMAGE_TOO_BLURRY
    }

    /**
     * Thresholds; all are proposals (제안값) until the contract meeting, set by preprocess.check.* on the server.
     *
     * @param minImageSide         shortest side of the upright original, pixels
     * @param minAnimalScore       a dog or cat counts when its score reaches this
     * @param secondAnimalScore    another animal makes the photo "too many" from this score ...
     * @param secondAnimalMinArea  ... and when its box is at least this fraction of the main animal's box
     * @param minAnimalPx          long side of the main animal's box in original pixels
     * @param minBrightness        {@link #brightnessPercentile} of the luma inside the box, 0..255
     * @param brightnessPercentile which percentile of the box's luma is compared (0.9: the bright tenth)
     * @param minSharpness         variance of the Laplacian inside the box
     * @param sharpnessPx          the box is reduced to this long side before the Laplacian
     */
    public record Rules(int minImageSide, float minAnimalScore, float secondAnimalScore, float secondAnimalMinArea,
                        int minAnimalPx, int minBrightness, double brightnessPercentile, double minSharpness,
                        int sharpnessPx) {
        /** secondAnimalScore equals minAnimalScore since 2026-11-05 (0.50 missed a real second dog at 0.48). */
        public static final Rules DEFAULT = new Rules(320, 0.40f, 0.40f, 0.15f, 224, 50, 0.90, 50.0, 512);
    }

    /**
     * @param reason     null when the photo passed
     * @param detail     developer text with the measured value (logged; never shown to users)
     * @param animal     the main animal (box in the pixels of the decoded photo), null when none counted
     * @param animals    every dog or cat the detector returned
     * @param width      upright width of the original, 0 when unreadable
     * @param height     upright height of the original, 0 when unreadable
     * @param scale      original pixels per decoded pixel
     * @param brightness the brightness percentile of the box, -1 when not measured
     * @param sharpness  the Laplacian variance of the box, -1 when not measured
     */
    public record Result(Reason reason, String detail, Detection animal, List<Detection> animals, int width,
                         int height, double scale, int brightness, double sharpness, long decodeMs, long detectMs,
                         long totalMs) {
        public boolean accepted() {
            return reason == null;
        }
    }

    private final AnimalDetector detector;
    private final Rules rules;
    private final ImageDecoding.Limits limits;
    private final int decodeLongSide;

    /**
     * @param decodeLongSide the photo is decoded at most this big (Unity uploads are at most 1600 px already)
     */
    public PhotoCheck(AnimalDetector detector, Rules rules, ImageDecoding.Limits limits, int decodeLongSide) {
        this.detector = detector;
        this.rules = rules;
        this.limits = limits;
        this.decodeLongSide = decodeLongSide;
    }

    /** Decodes and checks the bytes of an uploaded photo. */
    public Result check(byte[] photo) throws PreprocessException {
        long start = System.nanoTime();
        DecodedPhoto decoded;
        try {
            decoded = ImageDecoding.decode(photo, decodeLongSide, limits);
        } catch (UnreadableImageException e) {
            return new Result(Reason.UNREADABLE_IMAGE, e.getMessage(), null, List.of(), 0, 0, 1.0, -1, -1,
                    elapsed(start), 0, elapsed(start));
        }
        return check(decoded, start);
    }

    /** Checks a photo that is already decoded (the batch tool decodes once for every output). */
    public Result check(DecodedPhoto photo) throws PreprocessException {
        return check(photo, System.nanoTime() - photo.decodeMs() * 1_000_000);
    }

    private Result check(DecodedPhoto photo, long start) throws PreprocessException {
        int shortSide = Math.min(photo.width(), photo.height());
        if (shortSide < rules.minImageSide()) {
            return reject(photo, Reason.IMAGE_TOO_SMALL, "short side " + shortSide + " px < " + rules.minImageSide(),
                    null, List.of(), 0, start);
        }

        long detectStart = System.nanoTime();
        List<Detection> animals = List.copyOf(detector.detect(photo.image()));
        long detectMs = elapsed(detectStart);

        Detection main = null;
        for (Detection d : animals) {
            if (d.score() >= rules.minAnimalScore() && (main == null || d.box().area() > main.box().area())) {
                main = d; // the subject is the largest counted animal, not the best score
            }
        }
        if (main == null) {
            return reject(photo, Reason.NO_ANIMAL_DETECTED, "no dog or cat with score >= " + rules.minAnimalScore()
                    + " (" + describe(animals) + ")", null, animals, detectMs, start);
        }
        for (Detection d : animals) {
            if (d != main && d.score() >= rules.secondAnimalScore()
                    && d.box().area() >= rules.secondAnimalMinArea() * main.box().area()) {
                return reject(photo, Reason.TOO_MANY_ANIMALS, countAnimals(animals, main) + " animals ("
                        + describe(animals) + ")", main, animals, detectMs, start);
            }
        }
        double longSide = main.box().longSide() * photo.scale();
        if (longSide < rules.minAnimalPx()) {
            return reject(photo, Reason.ANIMAL_TOO_SMALL, String.format(Locale.ROOT, "animal %.0f px < %d", longSide,
                    rules.minAnimalPx()), main, animals, detectMs, start);
        }
        int brightness = PhotoQuality.lumaPercentile(photo.image(), main.box(), rules.brightnessPercentile());
        if (brightness < rules.minBrightness()) {
            return new Result(Reason.IMAGE_TOO_DARK, String.format(Locale.ROOT, "too dark (p%.0f brightness %d < %d)",
                    rules.brightnessPercentile() * 100, brightness, rules.minBrightness()), main, animals,
                    photo.width(), photo.height(), photo.scale(), brightness, -1, photo.decodeMs(), detectMs,
                    elapsed(start));
        }
        double sharpness = PhotoQuality.laplacianVariance(photo.image(), main.box(), rules.sharpnessPx());
        if (sharpness < rules.minSharpness()) {
            return new Result(Reason.IMAGE_TOO_BLURRY, String.format(Locale.ROOT, "too blurry (sharpness %.1f < %.1f)",
                    sharpness, rules.minSharpness()), main, animals, photo.width(), photo.height(), photo.scale(),
                    brightness, sharpness, photo.decodeMs(), detectMs, elapsed(start));
        }
        return new Result(null, String.format(Locale.ROOT, "%s %.2f, brightness %d, sharpness %.1f",
                className(main.classId()), main.score(), brightness, sharpness), main, animals, photo.width(),
                photo.height(), photo.scale(), brightness, sharpness, photo.decodeMs(), detectMs, elapsed(start));
    }

    private static Result reject(DecodedPhoto photo, Reason reason, String detail, Detection main,
                                 List<Detection> animals, long detectMs, long start) {
        return new Result(reason, detail, main, animals, photo.width(), photo.height(), photo.scale(), -1, -1,
                photo.decodeMs(), detectMs, elapsed(start));
    }

    private int countAnimals(List<Detection> animals, Detection main) {
        int count = 1;
        for (Detection d : animals) {
            if (d != main && d.score() >= rules.secondAnimalScore()
                    && d.box().area() >= rules.secondAnimalMinArea() * main.box().area()) {
                count++;
            }
        }
        return count;
    }

    static String describe(List<Detection> animals) {
        if (animals.isEmpty()) {
            return "none found";
        }
        StringBuilder text = new StringBuilder();
        for (Detection d : animals) {
            if (!text.isEmpty()) {
                text.append(", ");
            }
            text.append(String.format(Locale.ROOT, "%s %.2f", className(d.classId()), d.score()));
        }
        return text.toString();
    }

    static String className(int cocoId) {
        return switch (cocoId) {
            case YoloxCodec.COCO_CAT -> "cat";
            case YoloxCodec.COCO_DOG -> "dog";
            default -> "class " + cocoId;
        };
    }

    private static long elapsed(long startNanos) {
        return Math.max(0, (System.nanoTime() - startNanos) / 1_000_000);
    }
}
