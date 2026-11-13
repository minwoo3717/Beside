package com.example.mockbackend.preprocess;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The preprocessing core is plain Java (JDK + ONNX Runtime) so that the server and the offline batch tool run the
 * very same code with the same thresholds; Spring wiring lives in service/preprocess.
 */
class CorePurityTest {
    private static final List<String> FORBIDDEN = List.of("org.springframework", "jakarta.", "lombok");

    @Test
    void coreImportsNoFramework() throws IOException {
        Path core = Path.of("src/main/java/com/example/mockbackend/preprocess");
        assertThat(core).isDirectory();
        try (Stream<Path> files = Files.walk(core)) {
            List<String> offenders = files.filter(f -> f.toString().endsWith(".java"))
                    .flatMap(f -> lines(f).filter(CorePurityTest::forbidden).map(l -> f.getFileName() + ": " + l))
                    .toList();
            assertThat(offenders).isEmpty();
        }
    }

    private static boolean forbidden(String line) {
        String l = line.strip();
        return l.startsWith("import ") && FORBIDDEN.stream().anyMatch(l::contains);
    }

    private static Stream<String> lines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8).stream();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
