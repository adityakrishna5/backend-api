package com.store.common.security;

import org.springframework.security.oauth2.jwt.Jwt;
import java.util.List;
import java.util.Map;

public class JwtClaimsExtractor {

    private JwtClaimsExtractor() {}

    /**
     * Extracts Keycloak realm roles from the JWT claim {@code realm_access.roles}.
     */
    public static List<String> extractRoles(Jwt jwt) {
        @SuppressWarnings("unchecked")
        Map<String, Object> realmAccess = (Map<String, Object>) jwt.getClaims().get("realm_access");
        if (realmAccess == null) return List.of();
        @SuppressWarnings("unchecked")
        List<String> roles = (List<String>) realmAccess.get("roles");
        return roles != null ? roles : List.of();
    }

    public static String extractSubject(Jwt jwt) {
        return jwt.getSubject();
    }
}
