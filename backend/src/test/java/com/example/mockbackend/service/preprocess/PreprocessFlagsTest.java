package com.example.mockbackend.service.preprocess;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.InputStream;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The server runs the check and the cutout in real (and so prod); tests do not, unless a test turns them on itself.
 * A profile file beats src/test/resources/config/application.properties (backend/CLAUDE.md), so the test overrides are
 * profile files too (config/application-{real,mock,prod}.properties): this guards that they still win.
 */
class PreprocessFlagsTest {

    @Test
    void theRealServerHasBothStepsOn() throws Exception {
        Properties real = new Properties();
        try (InputStream in = PreprocessFlagsTest.class.getResourceAsStream("/application-real.properties")) {
            assertThat(in).isNotNull();
            real.load(in);
        }
        assertThat(real.getProperty("preprocess.check.enabled")).isEqualTo("true");
        assertThat(real.getProperty("preprocess.cutout.enabled")).isEqualTo("true");
    }

    @Nested
    @SpringBootTest
    @ActiveProfiles("real")
    class RealProfileInTests {
        @Autowired
        PreprocessProperties props;

        @Autowired
        PreprocessModels models;

        @Test
        void bothStepsAreOff() {
            assertThat(props.getCheck().isEnabled()).isFalse();
            assertThat(props.getCutout().isEnabled()).isFalse();
            assertThat(models.checkEnabled()).isFalse();
            assertThat(models.cutoutEnabled()).isFalse();
        }
    }

    @Nested
    @SpringBootTest
    @ActiveProfiles({"real", "prod"})
    class RealProdProfilesInTests {
        @Autowired
        PreprocessProperties props;

        @Test
        void bothStepsAreOff() {
            assertThat(props.getCheck().isEnabled()).isFalse();
            assertThat(props.getCutout().isEnabled()).isFalse();
        }
    }
}
