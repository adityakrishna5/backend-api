package com.store.order.config;

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
                .title("Order Processing API")
                .version("1.0.0")
                .description("""
                    Async order write service (port 8082).
                    Orders are published to Kafka topic 'order-events' for async processing.
                    On Kafka circuit-open, events are durably stored in the outbox_events table
                    and replayed by the polling sweeper when the broker recovers.
                    Returns HTTP 202 Accepted — order ID is the correlation key for tracking.
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
