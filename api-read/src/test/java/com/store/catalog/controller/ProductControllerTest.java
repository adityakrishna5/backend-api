package com.store.catalog.controller;

import com.store.catalog.service.ProductService;
import com.store.common.dto.ApiResponse;
import com.store.common.dto.ProductDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Web-layer slice tests for ProductController.
 * Uses @WebMvcTest to load only the web layer (no JPA, Redis, Kafka).
 * JwtDecoder is mocked to prevent startup calls to Keycloak.
 * @WithMockUser sets up a pre-authenticated principal, bypassing JWT decoding.
 */
@WebMvcTest(ProductController.class)
@DisplayName("ProductController Web Layer Tests")
class ProductControllerTest {

    @TestConfiguration
    @EnableMethodSecurity(prePostEnabled = true)
    static class MethodSecurityTestConfig {}

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ProductService productService;

    /** Prevents Spring Security auto-config from fetching JWKS from Keycloak at startup. */
    @MockBean
    private JwtDecoder jwtDecoder;

    // ── GET /api/v1/products/{id} ─────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "CUSTOMER")
    @DisplayName("GET /{id}: CUSTOMER role returns 200 with product data")
    void getProduct_customerRole_returns200() throws Exception {
        ProductDto dto = new ProductDto(1L, "Widget", "A widget", new BigDecimal("9.99"), 100);
        when(productService.getProduct(1L)).thenReturn(dto);

        mockMvc.perform(get("/api/v1/products/1").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.name").value("Widget"))
                .andExpect(jsonPath("$.data.price").value(9.99))
                .andExpect(jsonPath("$.data.stockLevel").value(100));
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    @DisplayName("GET /{id}: MANAGER role also returns 200")
    void getProduct_managerRole_returns200() throws Exception {
        ProductDto dto = new ProductDto(2L, "Gadget", "A gadget", BigDecimal.ONE, 5);
        when(productService.getProduct(2L)).thenReturn(dto);

        mockMvc.perform(get("/api/v1/products/2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Gadget"));
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    @DisplayName("GET /{id}: product not found returns 404 NOT_FOUND")
    void getProduct_notFound_returns404() throws Exception {
        when(productService.getProduct(99L))
                .thenThrow(new NoSuchElementException("Product not found: 99"));

        mockMvc.perform(get("/api/v1/products/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value("NOT_FOUND"))
                .andExpect(jsonPath("$.data").value("Product not found: 99"));
    }

    @Test
    @DisplayName("GET /{id}: unauthenticated request returns 401")
    void getProduct_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/products/1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "OTHER")
    @DisplayName("GET /{id}: role without CUSTOMER or MANAGER returns 403")
    void getProduct_insufficientRole_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/products/1"))
                .andExpect(status().isForbidden());
    }

    // ── GET /api/v1/products ─────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "CUSTOMER")
    @DisplayName("GET /: paginated list returns 200 with items")
    void listProducts_defaultPagination_returns200() throws Exception {
        when(productService.listProducts(0, 20)).thenReturn(List.of(
                new ProductDto(1L, "A", null, BigDecimal.ONE, 10),
                new ProductDto(2L, "B", null, BigDecimal.TEN, 20)
        ));

        mockMvc.perform(get("/api/v1/products").param("page", "0").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    @DisplayName("GET /: size > 100 returns 400 constraint violation")
    void listProducts_sizeExceedsMax_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/products").param("size", "200"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    @DisplayName("GET /: size < 1 returns 400 constraint violation")
    void listProducts_sizeZero_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/products").param("size", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    @DisplayName("GET /: negative page returns 400 constraint violation")
    void listProducts_negativePage_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/products").param("page", "-1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /: unauthenticated list request returns 401")
    void listProducts_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/products"))
                .andExpect(status().isUnauthorized());
    }

    // ── fallback (circuit breaker) ────────────────────────────────────────────

    @Test
    @DisplayName("fallback: circuit breaker open returns 503 with DEGRADED status")
    void fallback_circuitBreakerOpen_returns503Degraded() {
        ProductController controller = new ProductController(productService);
        ResponseEntity<ApiResponse<ProductDto>> response =
                controller.fallback(1L, new RuntimeException("circuit open"));

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo("DEGRADED");
        assertThat(response.getBody().data()).isNull();
    }
}
