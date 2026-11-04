package com.example.mockbackend.service.preprocess;

import com.example.mockbackend.preprocess.Cutout;
import com.example.mockbackend.preprocess.ImageDecoding;
import com.example.mockbackend.preprocess.PhotoCheck;
import com.example.mockbackend.preprocess.SaliencyCodec;
import com.example.mockbackend.preprocess.YoloxCodec;
import com.example.mockbackend.preprocess.YoloxDetector;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * preprocess.* — the first-photo check before the 202 and the cutout after it. The field defaults are the proposals
 * (제안값) and application.properties states the same values (PreprocessPropertiesTest); application-real.properties
 * turns both steps on. The batch tool binds the same files without a Spring context.
 */
@Component
@ConfigurationProperties(prefix = "preprocess")
@Getter
@Setter
public class PreprocessProperties {
    /** Folder with the .onnx files, relative to the working directory (backend/) unless absolute. */
    private String modelsDir = "models";
    private final CheckSettings check = new CheckSettings();
    private final CutoutSettings cutout = new CutoutSettings();
    private final DetectorSettings detector = new DetectorSettings();
    private final SegmenterSettings segmenter = new SegmenterSettings();
    private final DecodeSettings decode = new DecodeSettings();

    @Getter
    @Setter
    public static class CheckSettings {
        private boolean enabled = false;
        private int maxConcurrency = 1;
        private long waitMs = 3000;
        private int decodeLongSide = 1600;
        private int minImageSide = 320;
        private float minAnimalScore = 0.40f;
        private float secondAnimalScore = 0.50f;
        private float secondAnimalMinArea = 0.15f;
        private int minAnimalPx = 224;
        private int minBrightness = 50;
        private double brightnessPercentile = 0.90;
        private double minSharpness = 50.0;
        private int sharpnessPx = 512;
    }

    @Getter
    @Setter
    public static class CutoutSettings {
        private boolean enabled = false;
        /** remove (cut out) or keep (crop and pad only, for comparison). */
        private String background = "remove";
        private int maxConcurrency = 1;
        private long waitMs = 120_000;
        private int decodeLongSide = 2048;
        private double margin = 0.15;
        private double fill = 0.85;
        private int maxSide = 1024;
        private double minForeground = 0.05;
        private double maxForeground = 0.95;
        private boolean largestComponentOnly = true;
    }

    @Getter
    @Setter
    public static class DetectorSettings {
        private String model = "yolox_s.onnx";
        /** 0 = the model's own input size. */
        private int inputSize = 0;
        private String channelOrder = "BGR";
        private boolean legacyNormalize = false;
        private boolean decodedOutput = false;
        /** COCO 80-class indices: 15 cat, 16 dog. */
        private List<Integer> classIds = new ArrayList<>(List.of(YoloxCodec.COCO_CAT, YoloxCodec.COCO_DOG));
        private float candidateScore = 0.25f;
        private float nmsIou = 0.45f;
        private float containment = 0.7f;
        private int threads = 2;
        private boolean cpuArena = true;
    }

    @Getter
    @Setter
    public static class SegmenterSettings {
        private String model = "u2net.onnx";
        private int inputSize = 320;
        private List<Float> mean = new ArrayList<>(List.of(0.485f, 0.456f, 0.406f));
        private List<Float> std = new ArrayList<>(List.of(0.229f, 0.224f, 0.225f));
        private boolean divideByMax = true;
        private int outputIndex = 0;
        private boolean sigmoid = false;
        private int threads = 1;
        private boolean cpuArena = true;
    }

    @Getter
    @Setter
    public static class DecodeSettings {
        private long maxPixels = 64L * 1024 * 1024;
        private long maxWebpPixels = 16L * 1024 * 1024;
        private long limitMs = 5000;
    }

    public Path modelsPath() {
        return Path.of(modelsDir);
    }

    public ImageDecoding.Limits decodeLimits() {
        return new ImageDecoding.Limits(decode.maxPixels, decode.maxWebpPixels, decode.limitMs);
    }

    public PhotoCheck.Rules checkRules() {
        return new PhotoCheck.Rules(check.minImageSide, check.minAnimalScore, check.secondAnimalScore,
                check.secondAnimalMinArea, check.minAnimalPx, check.minBrightness, check.brightnessPercentile,
                check.minSharpness, check.sharpnessPx);
    }

    /** The cutout counts an animal from the same score as the check. */
    public Cutout.Rules cutoutRules() {
        return new Cutout.Rules(check.minAnimalScore, cutout.margin, cutout.fill, cutout.maxSide, cutout.minForeground,
                cutout.maxForeground, cutout.largestComponentOnly, background());
    }

    public Cutout.Background background() {
        try {
            return Cutout.Background.valueOf(cutout.background.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalStateException("preprocess.cutout.background must be remove or keep, got '"
                    + cutout.background + "'");
        }
    }

    public YoloxDetector.Spec detectorSpec() {
        YoloxCodec.ChannelOrder order;
        try {
            order = YoloxCodec.ChannelOrder.valueOf(detector.channelOrder.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalStateException("preprocess.detector.channel-order must be BGR or RGB, got '"
                    + detector.channelOrder + "'");
        }
        return new YoloxDetector.Spec(modelsPath().resolve(detector.model), detector.inputSize, order,
                detector.legacyNormalize, detector.decodedOutput,
                detector.classIds.stream().mapToInt(Integer::intValue).toArray(), detector.candidateScore,
                detector.nmsIou, detector.containment, detector.threads, detector.cpuArena);
    }

    public Path segmenterModel() {
        return modelsPath().resolve(segmenter.model);
    }

    public SaliencyCodec.Spec segmenterSpec() {
        if (segmenter.mean.size() != 3 || segmenter.std.size() != 3) {
            throw new IllegalStateException("preprocess.segmenter.mean and .std need 3 values (R, G, B)");
        }
        return new SaliencyCodec.Spec(segmenter.inputSize, floats(segmenter.mean), floats(segmenter.std),
                segmenter.divideByMax, segmenter.outputIndex, segmenter.sigmoid);
    }

    private static float[] floats(List<Float> values) {
        float[] out = new float[values.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = values.get(i);
        }
        return out;
    }
}
