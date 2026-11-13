package com.example.mockbackend.service.preprocess;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Which models load when, and failing fast with a usable message when one is missing. No model files needed. */
class PreprocessModelsTest {

    private static PreprocessProperties props(Path modelsDir, boolean check, boolean cutout) {
        PreprocessProperties p = new PreprocessProperties();
        p.setModelsDir(modelsDir.toString());
        p.getCheck().setEnabled(check);
        p.getCutout().setEnabled(cutout);
        return p;
    }

    private static MockEnvironment profile(String profile) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profile);
        return env;
    }

    @Test
    void offLoadsNothingEvenWithoutModels(@TempDir Path empty) {
        PreprocessModels models = new PreprocessModels(props(empty, false, false), profile("real"));

        assertThat(models.checkEnabled()).isFalse();
        assertThat(models.cutoutEnabled()).isFalse();
        assertThatThrownBy(models::detector).isInstanceOf(IllegalStateException.class);
        models.close();
    }

    @Test
    void theCutoutOnlyRunsWithTheRealWorker(@TempDir Path empty) {
        PreprocessModels models = new PreprocessModels(props(empty, false, true), profile("mock"));

        assertThat(models.cutoutEnabled()).as("mock never sends photos anywhere").isFalse();
        models.close();
    }

    @Test
    void aMissingModelStopsTheStartWithWhatToDo(@TempDir Path empty) {
        assertThatThrownBy(() -> new PreprocessModels(props(empty, true, false), profile("mock")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("yolox_s.onnx")
                .hasMessageContaining("fetch_models")
                .hasMessageContaining("PREPROCESS_MODELSDIR")
                .hasMessageContaining("PREPROCESS_CHECK_ENABLED=false");
        assertThatThrownBy(() -> new PreprocessModels(props(empty, false, true), profile("real")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("yolox_s.onnx");
    }

    /** Local only (-Dbeside.models / PREPROCESS_MODELSDIR, and on Windows a JDK with msvcp140 14.40+). */
    @Test
    @org.junit.jupiter.api.Tag("models")
    void loadsWarmsUpAndClosesTheRealModels() throws Exception {
        String dir = System.getProperty("beside.models", System.getenv("PREPROCESS_MODELSDIR"));
        org.junit.jupiter.api.Assumptions.assumeTrue(dir != null
                && java.nio.file.Files.isRegularFile(Path.of(dir, "yolox_s.onnx"))
                && java.nio.file.Files.isRegularFile(Path.of(dir, "u2net.onnx")), "no model folder");
        PreprocessModels models = new PreprocessModels(props(Path.of(dir), true, true), profile("real"));
        java.awt.image.BufferedImage grey = new java.awt.image.BufferedImage(320, 240,
                java.awt.image.BufferedImage.TYPE_3BYTE_BGR);

        assertThat(models.detector().name()).startsWith("yolox_s#");
        assertThat(models.segmenter().name()).startsWith("u2net#");
        assertThat(models.detector().detect(grey)).isEmpty();
        models.close();
        assertThatThrownBy(() -> models.detector().detect(grey))
                .isInstanceOf(com.example.mockbackend.preprocess.PreprocessException.class).hasMessageContaining("closed");
    }

    @Test
    void anUnknownBackgroundSettingIsRefusedAtStart(@TempDir Path empty) {
        PreprocessProperties p = props(empty, false, true);
        p.getCutout().setBackground("blur");

        assertThatThrownBy(() -> new PreprocessModels(p, profile("real")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("remove").hasMessageContaining("keep");
    }
}
