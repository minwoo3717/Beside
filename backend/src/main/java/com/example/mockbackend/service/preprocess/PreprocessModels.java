package com.example.mockbackend.service.preprocess;

import com.example.mockbackend.preprocess.AnimalDetector;
import com.example.mockbackend.preprocess.Cutout;
import com.example.mockbackend.preprocess.ForegroundSegmenter;
import com.example.mockbackend.preprocess.OnnxSaliencySegmenter;
import com.example.mockbackend.preprocess.PreprocessException;
import com.example.mockbackend.preprocess.YoloxDetector;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Loads the ONNX models the switched-on steps need, once, at startup: the detector for the check (any profile) and
 * for the cutout (real only), the segmenter for a cutout that removes the background. A needed model that is missing
 * or cannot load stops the start with what to do; with both steps off nothing loads (ONNX Runtime is never touched).
 * <p>
 * Runs go through wrappers that hold a read lock; closing at shutdown takes the write lock first, because closing an
 * ONNX session during a run can crash the JVM.
 */
@Component
public class PreprocessModels {
    private static final Logger log = LoggerFactory.getLogger(PreprocessModels.class);

    private final boolean checkEnabled;
    private final boolean cutoutEnabled;
    private final YoloxDetector yolox;
    private final OnnxSaliencySegmenter u2net;
    private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock(true);
    private volatile boolean closed;

    public PreprocessModels(PreprocessProperties props, Environment environment) {
        this.checkEnabled = props.getCheck().isEnabled();
        this.cutoutEnabled = props.getCutout().isEnabled() && environment.acceptsProfiles(Profiles.of("real"));
        Cutout.Background background = props.background(); // settings are checked even when nothing loads
        props.detectorSpec();
        props.segmenterSpec();

        boolean needDetector = checkEnabled || cutoutEnabled;
        boolean needSegmenter = cutoutEnabled && background == Cutout.Background.REMOVE;
        String step = checkEnabled ? "preprocess.check.enabled" : "preprocess.cutout.enabled";
        this.yolox = needDetector ? loadDetector(props, step) : null;
        this.u2net = needSegmenter ? loadSegmenter(props) : null;
        if (needDetector) {
            log.info("preprocess: check {}, cutout {} (background {}), detector {}{}", on(checkEnabled),
                    on(cutoutEnabled), background.name().toLowerCase(), yolox.name(),
                    u2net == null ? "" : ", segmenter " + u2net.name());
        } else {
            log.info("preprocess: check off, cutout off (no model loaded)");
        }
    }

    public boolean checkEnabled() {
        return checkEnabled;
    }

    public boolean cutoutEnabled() {
        return cutoutEnabled;
    }

    /** The detector, guarded against closing during a run. */
    public AnimalDetector detector() {
        if (yolox == null) {
            throw new IllegalStateException("no detector loaded: the check and the cutout are off");
        }
        return new AnimalDetector() {
            @Override
            public List<Detection> detect(BufferedImage image) throws PreprocessException {
                return guarded(() -> yolox.detect(image));
            }

            @Override
            public String name() {
                return yolox.name();
            }
        };
    }

    /** The segmenter, or null when the cutout keeps the background (or is off). */
    public ForegroundSegmenter segmenter() {
        if (u2net == null) {
            return null;
        }
        return new ForegroundSegmenter() {
            @Override
            public float[] segment(BufferedImage crop) throws PreprocessException {
                return guarded(() -> u2net.segment(crop));
            }

            @Override
            public String name() {
                return u2net.name();
            }
        };
    }

    @PreDestroy
    public void close() {
        if (yolox == null && u2net == null) {
            return;
        }
        Lock write = lifecycle.writeLock();
        try {
            if (!write.tryLock(30, TimeUnit.SECONDS)) {
                log.warn("preprocess models still in use after 30 s; left open (the process exit frees them)");
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        try {
            closed = true;
            if (yolox != null) {
                yolox.close();
            }
            if (u2net != null) {
                u2net.close();
            }
        } finally {
            write.unlock();
        }
    }

    private interface Run<T> {
        T run() throws PreprocessException;
    }

    private <T> T guarded(Run<T> run) throws PreprocessException {
        Lock read = lifecycle.readLock();
        read.lock();
        try {
            if (closed) {
                throw new PreprocessException("preprocess models are closed (shutting down)");
            }
            return run.run();
        } finally {
            read.unlock();
        }
    }

    private static YoloxDetector loadDetector(PreprocessProperties props, String step) {
        YoloxDetector.Spec spec = props.detectorSpec();
        requireFile(spec.model(), step);
        long start = System.nanoTime();
        try {
            YoloxDetector detector = YoloxDetector.load(spec);
            detector.detect(new BufferedImage(64, 64, BufferedImage.TYPE_3BYTE_BGR)); // warm-up: first run allocates
            log.info("preprocess: {} loaded and warmed up in {} ms ({} threads)", detector.name(),
                    (System.nanoTime() - start) / 1_000_000, spec.threads());
            return detector;
        } catch (PreprocessException | RuntimeException e) {
            throw new IllegalStateException(step + ": cannot use " + spec.model().toAbsolutePath() + ": "
                    + e.getMessage() + ". " + HOW_TO, e);
        }
    }

    private static OnnxSaliencySegmenter loadSegmenter(PreprocessProperties props) {
        Path model = props.segmenterModel();
        requireFile(model, "preprocess.cutout.enabled (background remove)");
        long start = System.nanoTime();
        try {
            OnnxSaliencySegmenter segmenter = OnnxSaliencySegmenter.load(model, props.segmenterSpec(),
                    props.getSegmenter().getThreads(), props.getSegmenter().isCpuArena());
            segmenter.segment(new BufferedImage(32, 32, BufferedImage.TYPE_3BYTE_BGR));
            log.info("preprocess: {} loaded and warmed up in {} ms ({} threads)", segmenter.name(),
                    (System.nanoTime() - start) / 1_000_000, props.getSegmenter().getThreads());
            return segmenter;
        } catch (PreprocessException | RuntimeException e) {
            throw new IllegalStateException("preprocess.cutout.enabled: cannot use " + model.toAbsolutePath() + ": "
                    + e.getMessage() + ". " + HOW_TO, e);
        }
    }

    private static final String HOW_TO = "Download the models with scripts/fetch_models.ps1 -Target <folder> (or "
            + "scripts/fetch_models.sh <folder>) and set PREPROCESS_MODELSDIR to that folder, see backend/models/README.md;"
            + " or start with PREPROCESS_CHECK_ENABLED=false and PREPROCESS_CUTOUT_ENABLED=false";

    private static void requireFile(Path model, String step) {
        if (!Files.isRegularFile(model)) {
            throw new IllegalStateException(step + " needs the model " + model.toAbsolutePath()
                    + ", which is missing. " + HOW_TO);
        }
    }

    private static String on(boolean value) {
        return value ? "on" : "off";
    }
}
