package com.store.catalog.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.*;
import io.swagger.v3.oas.models.security.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("Product Catalog API")
                .version("1.0.0")
                .description("""
                    Read-only product catalog service (port 8081).
                    All reads are served from Redis cache first — DB is hit only on cache miss.
                    Backed by Cloud SQL PostgreSQL read replicas for scalability.
                    """)
                .contact(new Contact().name("Store Platform Team")))
            .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
            .components(new io.swagger.v3.oas.models.Components()
                .addSecuritySchemes("bearerAuth",
                    new SecurityScheme()
                        .name("bearerAuth")
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("Keycloak JWT — obtain from /realms/store/protocol/openid-connect/token")));
    }
}
