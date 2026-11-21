package com.example.mockbackend;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The profile differences that docs/api/openapi.yaml (createJob) and docs/api/ERROR_CODES.md state for clients, and
 * the inference timeout decided on 2026-11-21, read from the server's own property files (not the test overrides):
 * <ul>
 *   <li>REVIEW_CHECKLIST #17: only prod checks the photo signature (upload.verify-magic, default false).</li>
 *   <li>REVIEW_CHECKLIST #19: the first-photo check is on in real (prod runs real,prod and inherits it), off in mock.</li>
 *   <li>REVIEW_CHECKLIST #7: inference.timeout-ms is 120 s in real, and prod does not override it.</li>
 * </ul>
 * Changing one of these changes what a client sees: change the two documents first.
 */
class ProfileContractSettingsTest {

    @Test
    void onlyProdChecksThePhotoSignature() throws IOException {
        assertThat(load("application-prod.properties").getProperty("upload.verify-magic")).isEqualTo("true");
        for (String file : new String[]{"application.properties", "application-mock.properties", "application-real.properties"}) {
            assertThat(load(file).getProperty("upload.verify-magic", "false")).as(file).isEqualTo("false");
        }
    }

    @Test
    void theFirstPhotoCheckIsOnInRealAndProdOffInMock() throws IOException {
        assertThat(load("application.properties").getProperty("preprocess.check.enabled")).isEqualTo("false");
        assertThat(load("application-real.properties").getProperty("preprocess.check.enabled")).isEqualTo("true");
        assertThat(load("application-mock.properties").getProperty("preprocess.check.enabled", "false")).isEqualTo("false");
        assertThat(load("application-prod.properties").getProperty("preprocess.check.enabled")).as("prod inherits real").isNull();
    }

    @Test
    void theInferenceTimeoutIsTwoMinutes() throws IOException {
        assertThat(load("application-real.properties").getProperty("inference.timeout-ms")).isEqualTo("120000");
        assertThat(load("application-prod.properties").getProperty("inference.timeout-ms")).as("prod inherits real").isNull();
    }

    /** The server's own file on the classpath root; the test overrides live in config/ and are not read here. */
    private static Properties load(String name) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = ProfileContractSettingsTest.class.getResourceAsStream("/" + name)) {
            assertThat(in).as(name).isNotNull();
            properties.load(in);
        }
        return properties;
    }
}
