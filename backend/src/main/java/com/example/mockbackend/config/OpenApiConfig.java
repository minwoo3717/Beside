package com.example.mockbackend.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * springdoc metadata for the generated /v3/api-docs. The hand-written contract docs/api/openapi.yaml is the
 * source of truth; JobV1ContractTest checks the generated document against it.
 */
@Configuration
public class OpenApiConfig {
    @Bean
    public OpenAPI besideOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Beside API")
                .version("1.0.0")
                .description("Generated from the running server. The contract of record is docs/api/openapi.yaml; "
                        + "JobV1ContractTest compares the two."));
    }
}
