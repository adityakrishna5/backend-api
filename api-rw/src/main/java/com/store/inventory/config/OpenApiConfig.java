package com.store.inventory.config;

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
                .title("Inventory Management API")
                .version("1.0.0")
                .description("""
                    Inventory read-write service (port 8083).

                    Two stock reservation paths:

                    **HOT PATH** — `POST /api/v1/inventory/{productId}/reserve-hot`
                    Uses Redis DECR. Atomic, ~100k ops/sec, ~0.1ms latency.
                    No DB lock. Guaranteed no oversell via Redis single-thread model.
                    Use for flash sales and high-throughput scenarios.

                    **STANDARD PATH** — `PUT /api/v1/inventory/{id}/reserve`
                    Uses Redisson distributed lock + PostgreSQL SELECT FOR UPDATE.
                    Fully DB-consistent, ~200 TPS/product. Use for admin/restock ops.
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
