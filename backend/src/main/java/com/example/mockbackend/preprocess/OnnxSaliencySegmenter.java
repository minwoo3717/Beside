package com.example.mockbackend.preprocess;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.Arrays;

/** {@link ForegroundSegmenter} on a salient-object ONNX model (U2Net by default), arithmetic in {@link SaliencyCodec}. */
public final class OnnxSaliencySegmenter implements ForegroundSegmenter, AutoCloseable {
    private final OnnxModel model;
    private final SaliencyCodec.Spec spec;

    private OnnxSaliencySegmenter(OnnxModel model, SaliencyCodec.Spec spec) {
        this.model = model;
        this.spec = spec;
    }

    public static OnnxSaliencySegmenter load(Path file, SaliencyCodec.Spec spec, int threads, boolean cpuArena)
            throws PreprocessException {
        OnnxModel model = OnnxModel.load(file, threads, cpuArena);
        long[] shape = model.inputShape();
        boolean fits = shape.length == 4 && (shape[2] < 0 || shape[2] == spec.inputSize())
                && (shape[3] < 0 || shape[3] == spec.inputSize());
        if (!fits || spec.outputIndex() >= model.outputCount()) {
            model.close();
            throw new PreprocessException(model.name() + " has input shape " + Arrays.toString(shape) + " and "
                    + model.outputCount() + " outputs; configured input " + spec.inputSize() + ", output "
                    + spec.outputIndex());
        }
        return new OnnxSaliencySegmenter(model, spec);
    }

    @Override
    public String name() {
        return model.name();
    }

    @Override
    public float[] segment(BufferedImage crop) throws PreprocessException {
        int s = spec.inputSize();
        OnnxModel.Output out = model.run(SaliencyCodec.tensor(crop, spec), new long[]{1, 3, s, s}, spec.outputIndex());
        if (out.data().length < s * s) {
            throw new PreprocessException(model.name() + " output " + Arrays.toString(out.shape()) + " is smaller than "
                    + s + " x " + s);
        }
        float[] plane = out.data().length == s * s ? out.data() : Arrays.copyOf(out.data(), s * s);
        return SaliencyCodec.mask(plane, s, spec, crop.getWidth(), crop.getHeight());
    }

    @Override
    public void close() {
        model.close();
    }
}
