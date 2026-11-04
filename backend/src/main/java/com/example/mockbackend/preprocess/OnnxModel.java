package com.example.mockbackend.preprocess;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtLoggingLevel;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;

import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * One ONNX Runtime session on the CPU. The model is read as bytes (native code never sees the path, which may hold
 * Korean characters on a dev PC). Runs are thread-safe; callers bound concurrency for CPU and memory, not safety.
 */
final class OnnxModel implements AutoCloseable {

    /** An output tensor copied out of the native result. */
    record Output(float[] data, long[] shape) {
    }

    private final OrtEnvironment env;
    private final OrtSession session;
    private final String inputName;
    private final long[] inputShape;
    private final List<String> outputNames;
    private final String name;

    private OnnxModel(OrtEnvironment env, OrtSession session, String inputName, long[] inputShape,
                      List<String> outputNames, String name) {
        this.env = env;
        this.session = session;
        this.inputName = inputName;
        this.inputShape = inputShape;
        this.outputNames = outputNames;
        this.name = name;
    }

    /**
     * @param threads  intra-op threads (the VM has 2 vCPUs; idle threads do not spin)
     * @param cpuArena ONNX Runtime's CPU arena: faster repeated runs, but holds on to its peak memory
     */
    static OnnxModel load(Path file, int threads, boolean cpuArena) throws PreprocessException {
        checkWindowsRuntime();
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new PreprocessException("cannot read model " + file + ": " + e.getMessage(), e);
        }
        String stem = file.getFileName().toString().replaceFirst("\\.onnx$", "");
        String name = stem + "#" + sha256Prefix(bytes);
        OrtEnvironment env = OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_WARNING, "beside");
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(Math.max(1, threads));
            options.setInterOpNumThreads(1);
            options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            options.setCPUArenaAllocator(cpuArena);
            options.setMemoryPatternOptimization(true);
            options.addConfigEntry("session.intra_op.allow_spinning", "0");
            options.addConfigEntry("session.inter_op.allow_spinning", "0");
            OrtSession session = env.createSession(bytes, options);
            Map.Entry<String, NodeInfo> input = session.getInputInfo().entrySet().iterator().next();
            long[] shape = input.getValue().getInfo() instanceof TensorInfo info ? info.getShape() : new long[0];
            return new OnnxModel(env, session, input.getKey(), shape, List.copyOf(session.getOutputNames()), name);
        } catch (OrtException | RuntimeException e) {
            throw new PreprocessException("cannot load model " + file.getFileName() + ": " + e.getMessage(), e);
        }
    }

    /** file stem # first 8 hex of the file's SHA-256, e.g. yolox_s#c5c2d13e (goes into preprocessVersion). */
    String name() {
        return name;
    }

    /** Static input shape, -1 where the model allows any size. */
    long[] inputShape() {
        return inputShape.clone();
    }

    int outputCount() {
        return outputNames.size();
    }

    /** Runs the model on one NCHW float tensor and copies output {@code outputIndex} out. */
    Output run(float[] chw, long[] shape, int outputIndex) throws PreprocessException {
        if (outputIndex < 0 || outputIndex >= outputNames.size()) {
            throw new PreprocessException(name + " has " + outputNames.size() + " outputs, no index " + outputIndex);
        }
        try (OnnxTensor tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(chw), shape);
             OrtSession.Result result = session.run(Map.of(inputName, tensor),
                     java.util.Set.of(outputNames.get(outputIndex)))) {
            OnnxTensor out = (OnnxTensor) result.get(0);
            FloatBuffer buffer = out.getFloatBuffer();
            float[] data = new float[buffer.remaining()];
            buffer.get(data);
            return new Output(data, out.getInfo().getShape());
        } catch (OrtException | RuntimeException e) {
            throw new PreprocessException(name + " failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        try {
            session.close();
        } catch (OrtException e) {
            // closing at shutdown: nothing left to do with it
        }
    }

    /**
     * Windows only. A JDK that ships its own bin\msvcp140.dll loads it at startup, and Windows then hands that copy
     * to onnxruntime.dll as well. ONNX Runtime is built with MSVC 17.10 or newer and crashes the whole JVM
     * (EXCEPTION_ACCESS_VIOLATION in msvcp140.dll, std::mutex) with a runtime older than 14.40 — e.g. the Red Hat
     * OpenJDK 17 build ships 14.29. Refuse to load instead of crashing. Linux (the VM, Docker, CI) is not affected.
     */
    static void checkWindowsRuntime() throws PreprocessException {
        if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows")) {
            return;
        }
        Path dll = Path.of(System.getProperty("java.home"), "bin", "msvcp140.dll");
        if (!Files.isRegularFile(dll)) {
            return; // this JDK uses the system's runtime
        }
        int[] version;
        try {
            version = peFileVersion(Files.readAllBytes(dll));
        } catch (IOException e) {
            return;
        }
        if (version != null && (version[0] < 14 || (version[0] == 14 && version[1] < 40))) {
            throw new PreprocessException("ONNX Runtime would crash with the old msvcp140.dll " + version[0] + "."
                    + version[1] + " that this JDK loads from " + dll.getParent() + "; run with a JDK that ships 14.40"
                    + " or newer (e.g. Microsoft Build of OpenJDK 17) or turn preprocessing off");
        }
    }

    /** File version (major, minor) from a PE file's VS_FIXEDFILEINFO, null when there is none. */
    static int[] peFileVersion(byte[] pe) {
        for (int i = 0; i + 16 <= pe.length; i++) {
            if ((pe[i] & 0xFF) == 0xBD && (pe[i + 1] & 0xFF) == 0x04 && (pe[i + 2] & 0xFF) == 0xEF
                    && (pe[i + 3] & 0xFF) == 0xFE) { // signature 0xFEEF04BD, little-endian
                java.nio.ByteBuffer fixed = java.nio.ByteBuffer.wrap(pe, i, 16).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                fixed.getInt(); // signature
                fixed.getInt(); // structure version
                int ms = fixed.getInt();
                return new int[]{ms >>> 16, ms & 0xFFFF};
            }
        }
        return null;
    }

    private static String sha256Prefix(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).substring(0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
