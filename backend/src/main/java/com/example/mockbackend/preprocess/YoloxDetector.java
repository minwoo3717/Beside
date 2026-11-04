package com.example.mockbackend.preprocess;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;

/** {@link AnimalDetector} on a YOLOX ONNX model (COCO 80 classes), arithmetic in {@link YoloxCodec}. */
public final class YoloxDetector implements AnimalDetector, AutoCloseable {

    /**
     * @param model           the .onnx file
     * @param inputSize       S; 0 = the model's static input size
     * @param order           channel order the export expects (official: BGR)
     * @param legacyNormalize exports before YOLOX 0.1.1 (RGB, /255, ImageNet statistics)
     * @param decodedOutput   the export decodes boxes itself (decode_in_inference=True)
     * @param classIds        COCO classes that count as animals here (15 cat, 16 dog)
     * @param candidateScore  candidates below this score are dropped before suppression
     * @param nmsIou          suppression overlap
     * @param containment     a box this much inside a better one is the same animal
     * @param threads         intra-op threads
     * @param cpuArena        ONNX Runtime CPU arena
     */
    public record Spec(Path model, int inputSize, YoloxCodec.ChannelOrder order, boolean legacyNormalize,
                       boolean decodedOutput, int[] classIds, float candidateScore, float nmsIou, float containment,
                       int threads, boolean cpuArena) {
        public static Spec defaults(Path model) {
            return new Spec(model, 0, YoloxCodec.ChannelOrder.BGR, false, false,
                    new int[]{YoloxCodec.COCO_CAT, YoloxCodec.COCO_DOG}, 0.25f, 0.45f, 0.7f, 2, true);
        }
    }

    private final OnnxModel model;
    private final Spec spec;
    private final int size;

    private YoloxDetector(OnnxModel model, Spec spec, int size) {
        this.model = model;
        this.spec = spec;
        this.size = size;
    }

    public static YoloxDetector load(Spec spec) throws PreprocessException {
        OnnxModel model = OnnxModel.load(spec.model(), spec.threads(), spec.cpuArena());
        long[] shape = model.inputShape();
        int fixed = shape.length == 4 && shape[2] > 0 && shape[2] == shape[3] ? (int) shape[2] : 0;
        int size = spec.inputSize() > 0 ? spec.inputSize() : fixed;
        if (size <= 0 || (fixed > 0 && size != fixed)) {
            model.close();
            throw new PreprocessException(model.name() + " has input shape " + java.util.Arrays.toString(shape)
                    + "; configured input size " + spec.inputSize());
        }
        return new YoloxDetector(model, spec, size);
    }

    public int inputSize() {
        return size;
    }

    @Override
    public String name() {
        return model.name();
    }

    @Override
    public List<Detection> detect(BufferedImage uprightBgr) throws PreprocessException {
        return run(uprightBgr, spec.classIds(), spec.candidateScore());
    }

    /** Every COCO class above {@code minScore}, suppressed across classes (diagnostics: what else did it see?). */
    public List<Detection> detectAll(BufferedImage uprightBgr, float minScore) throws PreprocessException {
        return run(uprightBgr, IntStream.range(0, 80).toArray(), minScore);
    }

    private List<Detection> run(BufferedImage image, int[] classIds, float minScore) throws PreprocessException {
        YoloxCodec.Letterbox in = YoloxCodec.letterbox(image, size, spec.order(), spec.legacyNormalize());
        OnnxModel.Output out = model.run(in.chw(), new long[]{1, 3, size, size}, 0);
        long[] shape = out.shape();
        if (shape.length != 3) {
            throw new PreprocessException(model.name() + " output shape " + java.util.Arrays.toString(shape)
                    + " is not [1, rows, 5 + classes]");
        }
        List<Detection> candidates = YoloxCodec.decode(out.data(), (int) shape[1], (int) shape[2], size, in.ratio(),
                image.getWidth(), image.getHeight(), classIds, minScore, spec.decodedOutput());
        return YoloxCodec.nms(candidates, spec.nmsIou(), spec.containment());
    }

    @Override
    public void close() {
        model.close();
    }
}
