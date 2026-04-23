package com.store.catalog.config;

import io.swagger.v3.oas.models.OpenAPI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("Config Unit Tests (api-read)")
class ProductConfigTest {

    // ── OpenApiConfig ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("OpenApiConfig.openAPI: returns non-null OpenAPI bean with correct title")
    void openApiConfig_openAPI_returnsBean() {
        OpenAPI openAPI = new OpenApiConfig().openAPI();
        assertThat(openAPI).isNotNull();
        assertThat(openAPI.getInfo().getTitle()).isEqualTo("Product Catalog API");
    }

    @Test
    @DisplayName("OpenApiConfig.openAPI: contains bearerAuth security scheme")
    void openApiConfig_openAPI_hasBearerAuthScheme() {
        OpenAPI openAPI = new OpenApiConfig().openAPI();
        assertThat(openAPI.getComponents().getSecuritySchemes()).containsKey("bearerAuth");
    }

    // ── DataSourceConfig ──────────────────────────────────────────────────────

    @Test
    @DisplayName("DataSourceConfig: can be instantiated")
    void dataSourceConfig_canBeInstantiated() {
        assertThat(new DataSourceConfig()).isNotNull();
    }

    // ── RedisConfig ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("RedisConfig.cacheManager: returns a non-null RedisCacheManager")
    void redisConfig_cacheManager_returnsCacheManager() {
        RedisConfig config = new RedisConfig();
        ReflectionTestUtils.setField(config, "ttlMillis", 60_000L);

        RedisConnectionFactory mockFactory = mock(RedisConnectionFactory.class);
        RedisCacheManager manager = (RedisCacheManager) config.cacheManager(mockFactory);

        assertThat(manager).isNotNull();
    }
}
