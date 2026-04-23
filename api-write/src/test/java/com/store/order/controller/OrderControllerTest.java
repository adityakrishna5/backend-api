package com.store.order.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.store.common.dto.OrderRequest;
import com.store.order.service.OrderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Web-layer slice tests for OrderController.
 * Uses @WebMvcTest — only the web layer is loaded.
 * OrderService is mocked; JwtDecoder is mocked to suppress Keycloak startup calls.
 *
 * TestSecurityConfig replaces the default Spring Security filter chain to:
 *   - disable CSRF (required for POST/PUT slice tests)
 *   - return 401 for unauthenticated requests (mirrors production JWT resource server behaviour)
 *   - enable @PreAuthorize method-level security
 */
@WebMvcTest(OrderController.class)
@DisplayName("OrderController Web Layer Tests")
class OrderControllerTest {

    @TestConfiguration
    @EnableMethodSecurity(prePostEnabled = true)
    static class TestSecurityConfig {
        @Bean
        SecurityFilterChain testFilterChain(HttpSecurity http) throws Exception {
            return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                    .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                )
                .build();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private OrderService orderService;

    /** Prevents NimbusJwtDecoder from connecting to Keycloak at startup. */
    @MockBean
    private JwtDecoder jwtDecoder;

    private OrderRequest validRequest() {
        return new OrderRequest(1L, 2);
    }

    // ── POST /api/v1/orders ──────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "CUSTOMER")
    @DisplayName("POST /orders: CUSTOMER places order returns 202 Accepted with orderId")
    void placeOrder_customer_returns202() throws Exception {
        when(orderService.publishOrderEvent(any())).thenReturn("order-uuid-111");

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.data").value("order-uuid-111"));
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    @DisplayName("POST /orders: MANAGER can also place order, returns 202")
    void placeOrder_manager_returns202() throws Exception {
        when(orderService.publishOrderEvent(any())).thenReturn("mgr-order-222");

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data").value("mgr-order-222"));
    }

    @Test
    @DisplayName("POST /orders: unauthenticated returns 401")
    void placeOrder_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    @DisplayName("POST /orders: missing productId returns 400 validation error")
    void placeOrder_missingProductId_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\": 2}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("VALIDATION_ERROR"));
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    @DisplayName("POST /orders: quantity = 0 returns 400 validation error")
    void placeOrder_zeroQuantity_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\": 1, \"quantity\": 0}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    @DisplayName("POST /orders: quantity > 1000 returns 400 validation error")
    void placeOrder_quantityOverMax_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\": 1, \"quantity\": 9999}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    @DisplayName("POST /orders: empty body returns 400")
    void placeOrder_emptyBody_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    // ── PUT /api/v1/orders/{orderId} ─────────────────────────────────────────

    @Test
    @WithMockUser(roles = "MANAGER")
    @DisplayName("PUT /orders/{orderId}: MANAGER updates order returns 202 Accepted with eventId")
    void updateOrder_manager_returns202() throws Exception {
        when(orderService.publishOrderUpdateEvent(eq("order-uuid-abc"), any()))
                .thenReturn("event-id-789");

        mockMvc.perform(put("/api/v1/orders/order-uuid-abc")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.data").value("event-id-789"));
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    @DisplayName("PUT /orders/{orderId}: CUSTOMER role returns 403 Forbidden")
    void updateOrder_customer_returns403() throws Exception {
        mockMvc.perform(put("/api/v1/orders/order-uuid-abc")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PUT /orders/{orderId}: unauthenticated returns 401")
    void updateOrder_unauthenticated_returns401() throws Exception {
        mockMvc.perform(put("/api/v1/orders/order-uuid-abc")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    @DisplayName("PUT /orders/{orderId}: invalid body returns 400")
    void updateOrder_invalidBody_returns400() throws Exception {
        mockMvc.perform(put("/api/v1/orders/order-uuid-abc")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\": 0}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    @DisplayName("PUT /orders/{orderId}: service call uses orderId from path variable")
    void updateOrder_passesOrderIdToService() throws Exception {
        when(orderService.publishOrderUpdateEvent(anyString(), any())).thenReturn("evt-x");

        mockMvc.perform(put("/api/v1/orders/specific-order-id")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isAccepted());

        verify(orderService).publishOrderUpdateEvent(eq("specific-order-id"), any());
    }
}
