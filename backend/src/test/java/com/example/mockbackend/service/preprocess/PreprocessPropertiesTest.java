package com.example.mockbackend.service.preprocess;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.io.InputStream;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * application.properties writes every preprocessing setting down (the batch tool reads the same file), and the values
 * there are the Java defaults: one place to read the proposals (제안값), no drift between the two.
 */
class PreprocessPropertiesTest {

    @Test
    void theFileStatesTheJavaDefaults() throws Exception {
        PreprocessProperties fromFile = bind("/application.properties");
        PreprocessProperties defaults = new PreprocessProperties();

        assertThat(fromFile.getCheck().isEnabled()).as("off unless a profile turns it on").isFalse();
        assertThat(fromFile.getCutout().isEnabled()).isFalse();
        assertThat(fromFile.getModelsDir()).isEqualTo(defaults.getModelsDir());
        assertThat(fromFile.checkRules()).isEqualTo(defaults.checkRules());
        assertThat(fromFile.cutoutRules()).isEqualTo(defaults.cutoutRules());
        assertThat(fromFile.decodeLimits()).isEqualTo(defaults.decodeLimits());
        assertThat(fromFile.detectorSpec()).usingRecursiveComparison().isEqualTo(defaults.detectorSpec());
        assertThat(fromFile.segmenterSpec()).usingRecursiveComparison().isEqualTo(defaults.segmenterSpec());
        assertThat(fromFile.getCheck()).usingRecursiveComparison().isEqualTo(defaults.getCheck());
        assertThat(fromFile.getCutout()).usingRecursiveComparison().isEqualTo(defaults.getCutout());
    }

    @Test
    void theDefaultsAreTheProposalsOfTheContract() {
        PreprocessProperties p = new PreprocessProperties();

        // ERROR_CODES.md "대표 사진 검사" and REVIEW_CHECKLIST #19 quote these numbers
        assertThat(p.checkRules().minImageSide()).isEqualTo(320);
        assertThat(p.checkRules().minAnimalScore()).isEqualTo(0.40f);
        assertThat(p.checkRules().secondAnimalScore()).isEqualTo(0.40f);
        assertThat(p.checkRules().secondAnimalMinArea()).isEqualTo(0.15f);
        assertThat(p.checkRules().minAnimalPx()).isEqualTo(224);
        assertThat(p.checkRules().minBrightness()).isEqualTo(50);
        assertThat(p.checkRules().brightnessPercentile()).isEqualTo(0.90);
        assertThat(p.checkRules().minSharpness()).isEqualTo(50.0);
        assertThat(p.checkRules().sharpnessPx()).isEqualTo(512);
        assertThat(p.detectorSpec().classIds()).containsExactly(15, 16);
        assertThat(p.detectorSpec().model().getFileName()).hasToString("yolox_s.onnx");
        assertThat(p.segmenterModel().getFileName()).hasToString("u2net.onnx");
    }

    static PreprocessProperties bind(String resource) throws Exception {
        Properties file = new Properties();
        try (InputStream in = PreprocessPropertiesTest.class.getResourceAsStream(resource)) {
            assertThat(in).as(resource).isNotNull();
            file.load(in);
        }
        return new Binder(new MapConfigurationPropertySource(file)).bind("preprocess", PreprocessProperties.class)
                .get();
    }
}
