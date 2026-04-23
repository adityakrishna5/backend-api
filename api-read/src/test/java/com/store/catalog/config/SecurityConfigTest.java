package com.store.catalog.config;

import com.store.catalog.controller.ProductController;
import com.store.catalog.service.ProductService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests for SecurityConfig.
 * The @WebMvcTest + @Import(SecurityConfig.class) loads the real filter chain
 * bean, covering filterChain() and jwtConverter() in the Spring context.
 * Unit tests below cover the jwtConverter lambda branches directly.
 */
@WebMvcTest(ProductController.class)
@Import(SecurityConfig.class)
@DisplayName("SecurityConfig Tests (api-read)")
class SecurityConfigTest {

    @MockBean
    private JwtDecoder jwtDecoder;

    @MockBean
    private ProductService productService;

    @Autowired
    private MockMvc mockMvc;

    // ── filterChain: integration via @WebMvcTest ──────────────────────────────

    @Test
    @DisplayName("filterChain: unauthenticated request returns 401")
    void filterChain_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/products/1"))
                .andExpect(status().isUnauthorized());
    }

    // ── jwtConverter lambda: unit tests ──────────────────────────────────────

    @Test
    @DisplayName("jwtConverter: realm_access with roles → ROLE_xxx authorities")
    void jwtConverter_withRealmAccess_extractsRoleAuthorities() {
        SecurityConfig config = new SecurityConfig();
        JwtAuthenticationConverter converter = config.jwtConverter();

        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .subject("user1")
                .claim("realm_access", Map.of("roles", List.of("CUSTOMER", "MANAGER")))
                .build();

        AbstractAuthenticationToken token = converter.convert(jwt);
        assertThat(token).isNotNull();
        assertThat(token.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_CUSTOMER", "ROLE_MANAGER");
    }

    @Test
    @DisplayName("jwtConverter: no realm_access claim → empty authorities")
    void jwtConverter_withNullRealmAccess_returnsEmptyAuthorities() {
        SecurityConfig config = new SecurityConfig();
        JwtAuthenticationConverter converter = config.jwtConverter();

        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .subject("user2")
                .build();

        AbstractAuthenticationToken token = converter.convert(jwt);
        assertThat(token).isNotNull();
        assertThat(token.getAuthorities()).isEmpty();
    }
}
