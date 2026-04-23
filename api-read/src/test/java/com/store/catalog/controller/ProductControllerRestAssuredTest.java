package com.store.catalog.controller;

import com.store.catalog.service.ProductService;
import com.store.common.dto.ProductDto;
import io.restassured.module.mockmvc.RestAssuredMockMvc;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;

import static io.restassured.module.mockmvc.RestAssuredMockMvc.given;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

/**
 * RestAssured (RestAssuredMockMvc) tests for ProductController.
 * Delegates to the same MockMvc setup as ProductControllerTest — no real HTTP server started.
 * Security is applied via RequestPostProcessor, not @WithMockUser, so it works with RestAssured's DSL.
 */
@WebMvcTest(ProductController.class)
@DisplayName("ProductController RestAssured Tests")
class ProductControllerRestAssuredTest {

    @TestConfiguration
    @EnableMethodSecurity(prePostEnabled = true)
    static class MethodSecurityTestConfig {}

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ProductService productService;

    @MockBean
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void configureRestAssured() {
        RestAssuredMockMvc.mockMvc(mockMvc);
    }

    @Test
    @DisplayName("GET /api/v1/products/{id}: CUSTOMER gets 200 with product payload")
    void getProduct_customer_returns200() {
        ProductDto dto = new ProductDto(1L, "Widget", "desc", new BigDecimal("9.99"), 50);
        when(productService.getProduct(1L)).thenReturn(dto);

        given()
            .postProcessors(user("customer").roles("CUSTOMER"))
        .when()
            .get("/api/v1/products/1")
        .then()
            .statusCode(200)
            .body("status", equalTo("OK"))
            .body("data.id", equalTo(1))
            .body("data.name", equalTo("Widget"))
            .body("data.price", equalTo(9.99f));
    }

    @Test
    @DisplayName("GET /api/v1/products/{id}: MANAGER gets 200")
    void getProduct_manager_returns200() {
        ProductDto dto = new ProductDto(3L, "Gadget", null, BigDecimal.ONE, 5);
        when(productService.getProduct(3L)).thenReturn(dto);

        given()
            .postProcessors(user("mgr").roles("MANAGER"))
        .when()
            .get("/api/v1/products/3")
        .then()
            .statusCode(200)
            .body("data.name", equalTo("Gadget"));
    }

    @Test
    @DisplayName("GET /api/v1/products/{id}: unauthenticated returns 401")
    void getProduct_noAuth_returns401() {
        given()
        .when()
            .get("/api/v1/products/1")
        .then()
            .statusCode(401);
    }

    @Test
    @DisplayName("GET /api/v1/products/{id}: product not found returns 404")
    void getProduct_notFound_returns404() {
        when(productService.getProduct(10L))
                .thenThrow(new NoSuchElementException("Product not found: 10"));

        given()
            .postProcessors(user("customer").roles("CUSTOMER"))
        .when()
            .get("/api/v1/products/10")
        .then()
            .statusCode(404)
            .body("status", equalTo("NOT_FOUND"))
            .body("data", containsString("Product not found: 10"));
    }

    @Test
    @DisplayName("GET /api/v1/products: paginated list returns 200 with items")
    void listProducts_returns200WithItems() {
        when(productService.listProducts(0, 10)).thenReturn(List.of(
                new ProductDto(1L, "A", null, BigDecimal.ONE, 5),
                new ProductDto(2L, "B", null, BigDecimal.TEN, 15)
        ));

        given()
            .postProcessors(user("customer").roles("CUSTOMER"))
            .param("page", 0)
            .param("size", 10)
        .when()
            .get("/api/v1/products")
        .then()
            .statusCode(200)
            .body("data.size()", equalTo(2))
            .body("data[0].name", equalTo("A"))
            .body("data[1].name", equalTo("B"));
    }

    @Test
    @DisplayName("GET /api/v1/products: size > 100 returns 400")
    void listProducts_sizeOverMax_returns400() {
        given()
            .postProcessors(user("customer").roles("CUSTOMER"))
            .param("size", 201)
        .when()
            .get("/api/v1/products")
        .then()
            .statusCode(400);
    }

    @Test
    @DisplayName("GET /api/v1/products: wrong role returns 403")
    void listProducts_wrongRole_returns403() {
        given()
            .postProcessors(user("guest").roles("GUEST"))
        .when()
            .get("/api/v1/products")
        .then()
            .statusCode(403);
    }
}
