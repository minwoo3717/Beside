package com.example.mockbackend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
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
 * paths x methods, operationIds, response status codes, component schema names, the properties of every contract
 * schema (allOf flattened), every enum on a schema, a property or a parameter (status, variant, species, ?format=, ...)
 * and the ErrorResponse envelope.
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

        // 4. Every schema of the contract has exactly its properties (allOf flattened; additions must be written into
        //    the contract) and the same enum values on the schema and on each property (species, status, variant, ...).
        //    Until 2026-11-21 only JobResponse and ErrorDetail were compared, so the ~30 schemas of v1.2 could drift.
        for (String schema : names(contract.at("/components/schemas"))) {
            JsonNode expectedSchema = contract.at("/components/schemas/" + schema);
            JsonNode actualSchema = generated.at("/components/schemas/" + schema);
            Map<String, JsonNode> expected = properties(contract, expectedSchema);
            Map<String, JsonNode> actual = properties(generated, actualSchema);
            assertThat(actual.keySet()).as("%s properties", schema).containsExactlyInAnyOrderElementsOf(expected.keySet());
            assertThat(enumOf(generated, actualSchema)).as("%s enum", schema)
                    .containsExactlyInAnyOrderElementsOf(enumOf(contract, expectedSchema));
            expected.forEach((property, node) -> assertThat(enumOf(generated, actual.get(property)))
                    .as("%s.%s enum", schema, property)
                    .containsExactlyInAnyOrderElementsOf(enumOf(contract, node)));
        }
        // ... and every parameter the contract gives an enum (?variant=, the admin exports' ?format=) has it in code too.
        contractOps.forEach((operation, expected) -> {
            for (JsonNode candidate : expected.path("parameters")) {
                JsonNode parameter = resolve(contract, candidate);
                List<String> values = enumOf(contract, parameter.path("schema"));
                if (!values.isEmpty()) {
                    String name = parameter.path("name").asText();
                    assertThat(enumOf(generated, parameter(generated, generatedOps.get(operation), name).path("schema")))
                            .as("%s ?%s= enum", operation, name)
                            .containsExactlyInAnyOrderElementsOf(values);
                }
            }
        });

        // 5. Enum values (also covered by 4; kept as the named checks of the app's two enums).
        assertThat(enumValues(generated, generated.at("/components/schemas/JobResponse/properties/status")))
                .as("JobResponse.status enum")
                .containsExactlyInAnyOrderElementsOf(
                        enumValues(contract, contract.at("/components/schemas/JobResponse/properties/status")));
        assertThat(enumValues(generated, generated.at("/components/schemas/AssetInfo/properties/variant")))
                .as("AssetInfo.variant enum")
                .containsExactlyInAnyOrderElementsOf(
                        enumValues(contract, contract.at("/components/schemas/AssetInfo/properties/variant")));
        // The request side of the same enum: GET .../asset?variant= (hair left both on 2026-11-21, REVIEW_CHECKLIST #4).
        String download = "GET /api/v1/jobs/{jobId}/asset";
        assertThat(enumValues(generated, parameter(generated, generatedOps.get(download), "variant").path("schema")))
                .as("%s ?variant= enum", download)
                .isNotEmpty()
                .containsExactlyInAnyOrderElementsOf(
                        enumValues(contract, parameter(contract, contractOps.get(download), "variant").path("schema")));

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

    /** The operation's parameter called {@code name}, following a $ref to components/parameters; missing if none. */
    private static JsonNode parameter(JsonNode document, JsonNode operation, String name) {
        for (JsonNode candidate : operation.path("parameters")) {
            JsonNode resolved = resolve(document, candidate);
            if (name.equals(resolved.path("name").asText())) {
                return resolved;
            }
        }
        return MissingNode.getInstance();
    }

    private static List<String> enumValues(JsonNode document, JsonNode node) {
        List<String> values = new ArrayList<>();
        resolve(document, node).path("enum").forEach(value -> values.add(value.asText()));
        return values;
    }

    /** Property name → node of a schema, following $ref and merging allOf parts (PetDetailResponse = PetResponse + more). */
    private static Map<String, JsonNode> properties(JsonNode document, JsonNode schema) {
        Map<String, JsonNode> properties = new TreeMap<>();
        JsonNode resolved = resolve(document, schema);
        for (JsonNode part : resolved.path("allOf")) {
            properties.putAll(properties(document, part));
        }
        resolved.path("properties").fields().forEachRemaining(field -> properties.put(field.getKey(), field.getValue()));
        return properties;
    }

    /**
     * The enum values of a schema or property however it is written: inline, behind a $ref, inside allOf (the
     * contract's nullable references) or on array items. Empty when it has none.
     */
    private static List<String> enumOf(JsonNode document, JsonNode node) {
        JsonNode resolved = resolve(document, node);
        List<String> values = enumValues(document, resolved);
        if (values.isEmpty()) {
            for (JsonNode part : resolved.path("allOf")) {
                values.addAll(enumOf(document, part));
            }
        }
        if (values.isEmpty() && resolved.has("items")) {
            values.addAll(enumOf(document, resolved.get("items")));
        }
        return values;
    }
}
