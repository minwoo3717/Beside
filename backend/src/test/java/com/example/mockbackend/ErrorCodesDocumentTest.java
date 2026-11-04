package com.example.mockbackend;

import com.example.mockbackend.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/api/ERROR_CODES.md and exception/ErrorCode stay 1:1, as both of them say: every HTTP code with the same
 * status, every job failure code in the job table, nothing extra on either side. Apps pick their text by code, so a
 * code missing from the document would reach users as the generic line.
 */
class ErrorCodesDocumentTest {

    @Test
    void documentAndEnumListTheSameCodes() throws IOException {
        Path doc = Path.of(System.getProperty("beside.errorCodes", "../docs/api/ERROR_CODES.md"));
        List<String> lines = Files.readAllLines(doc, StandardCharsets.UTF_8);

        Map<String, Integer> httpCodes = new LinkedHashMap<>();
        Set<String> jobCodes = new LinkedHashSet<>();
        String section = "";
        for (String line : lines) {
            if (line.startsWith("## ")) { // "### " subsections stay inside their section
                section = line;
                continue;
            }
            if (!line.startsWith("| `")) {
                continue;
            }
            String[] cells = line.split("\\|");
            String code = cells[1].trim().replace("`", "");
            if (section.contains("HTTP")) {
                httpCodes.put(code, Integer.parseInt(cells[2].trim()));
            } else if (section.contains("Job")) {
                jobCodes.add(code);
            }
        }

        Map<String, Integer> enumHttp = Arrays.stream(ErrorCode.values()).filter(c -> c.httpStatus() != null)
                .collect(Collectors.toMap(Enum::name, c -> c.httpStatus().value(), (a, b) -> a, LinkedHashMap::new));
        Set<String> enumJobOnly = Arrays.stream(ErrorCode.values()).filter(c -> c.httpStatus() == null)
                .map(Enum::name).collect(Collectors.toCollection(LinkedHashSet::new));

        assertThat(httpCodes).as("HTTP table of %s", doc).containsExactlyInAnyOrderEntriesOf(enumHttp);
        assertThat(jobCodes).as("job failure table of %s", doc).containsAll(enumJobOnly);
        // A job failure code is job-only or an HTTP code reused for a failed job (INTERNAL_ERROR).
        assertThat(jobCodes).allSatisfy(code -> assertThat(enumJobOnly.contains(code) || enumHttp.containsKey(code))
                .as("job failure code %s exists in ErrorCode", code).isTrue());
    }
}
