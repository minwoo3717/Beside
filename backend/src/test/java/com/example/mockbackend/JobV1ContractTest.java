package com.example.mockbackend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contract test: the document of record (docs/api/openapi.yaml) versus what springdoc generates from the
 * running code (/v3/api-docs). Compared structurally, not byte for byte:
 * paths x methods, operationIds, response status codes, component schema names, JobResponse properties,
 * the status / variant enums and the ErrorResponse envelope.
 * If this fails, fix the document first (contract meeting rules in CLAUDE.md), then the code.
 */
@SpringBootTest
@AutoConfigureMockMvc
class JobV1ContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    @Autowired
    MockMvc mvc;

    @Test
    void generatedApiDocsMatchContractDocument() throws Exception {
        Path contractPath = Path.of(System.getProperty("beside.openapi", "../docs/api/openapi.yaml"));
        assertThat(Files.isRegularFile(contractPath))
                .as("contract document at %s (override with -Dbeside.openapi=...)", contractPath.toAbsolutePath())
                .isTrue();
        JsonNode contract = YAML.readTree(contractPath.toFile());
        JsonNode generated = JSON.readTree(mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray());

        // 1. Same set of operations (only /api/v1/** is documented by springdoc).
        Map<String, JsonNode> contractOps = operations(contract);
        Map<String, JsonNode> generatedOps = operations(generated);
        assertThat(generatedOps.keySet()).as("paths x methods")
                .containsExactlyInAnyOrderElementsOf(contractOps.keySet());

        // 2. Per operation: operationId and declared response status codes.
        contractOps.forEach((operation, expected) -> {
            JsonNode actual = generatedOps.get(operation);
            assertThat(actual.path("operationId").asText()).as("operationId of %s", operation)
                    .isEqualTo(expected.path("operationId").asText());
            assertThat(names(actual.path("responses"))).as("response codes of %s", operation)
                    .containsExactlyInAnyOrderElementsOf(names(expected.path("responses")));
        });

        // 3. Every schema named in the contract exists in the generated document (same class names).
        assertThat(names(generated.at("/components/schemas"))).as("component schemas")
                .containsAll(names(contract.at("/components/schemas")));

        // 4. JobResponse has exactly the contract properties (additions must be written into the contract).
        assertThat(names(generated.at("/components/schemas/JobResponse/properties"))).as("JobResponse properties")
                .containsExactlyInAnyOrderElementsOf(names(contract.at("/components/schemas/JobResponse/properties")));

        // 5. Enum values.
        assertThat(enumValues(generated, generated.at("/components/schemas/JobResponse/properties/status")))
                .as("JobResponse.status enum")
                .containsExactlyInAnyOrderElementsOf(
                        enumValues(contract, contract.at("/components/schemas/JobResponse/properties/status")));
        assertThat(enumValues(generated, generated.at("/components/schemas/AssetInfo/properties/variant")))
                .as("AssetInfo.variant enum")
                .containsExactlyInAnyOrderElementsOf(
                        enumValues(contract, contract.at("/components/schemas/AssetInfo/properties/variant")));

        // 6. Error envelope.
        assertThat(names(generated.at("/components/schemas/ErrorResponse/properties"))).containsExactly("error");
        assertThat(names(generated.at("/components/schemas/ErrorDetail/properties"))).as("ErrorDetail properties")
                .containsExactlyInAnyOrderElementsOf(names(contract.at("/components/schemas/ErrorDetail/properties")));
    }

    /** "METHOD /path" → operation node. */
    private static Map<String, JsonNode> operations(JsonNode document) {
        Map<String, JsonNode> operations = new TreeMap<>();
        document.path("paths").fields().forEachRemaining(path ->
                path.getValue().fields().forEachRemaining(method -> {
                    String name = method.getKey();
                    if (!name.startsWith("x-") && !name.equals("parameters") && !name.equals("summary")
                            && !name.equals("description") && !name.equals("servers")) {
                        operations.put(name.toUpperCase(Locale.ROOT) + " " + path.getKey(), method.getValue());
                    }
                }));
        return operations;
    }

    private static List<String> names(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    /** Follows local $ref pointers (the contract may use components; springdoc inlines enums). */
    private static JsonNode resolve(JsonNode document, JsonNode node) {
        JsonNode current = node;
        for (int hops = 0; current.has("$ref") && hops < 10; hops++) {
            current = document.at(current.get("$ref").asText().substring(1));
        }
        return current;
    }

    private static List<String> enumValues(JsonNode document, JsonNode node) {
        List<String> values = new ArrayList<>();
        resolve(document, node).path("enum").forEach(value -> values.add(value.asText()));
        return values;
    }
}
