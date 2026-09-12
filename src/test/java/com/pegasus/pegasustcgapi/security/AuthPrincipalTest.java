package com.pegasus.pegasustcgapi.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.UnauthorizedException;
import com.pegasus.pegasustcgapi.model.RoleCode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

@DisplayName("AuthPrincipal")
class AuthPrincipalTest {

    @Test
    @DisplayName("reads the caller out of a verified token")
    void readsClaims() {
        AuthPrincipal principal = AuthPrincipal.from(jwt(Map.of(
                "sub", "42",
                AuthClaims.EMAIL, "ploy@example.com",
                AuthClaims.USERNAME, "ploy",
                AuthClaims.ROLES, List.of("BUYER", "SELLER"))));

        assertThat(principal.userId()).isEqualTo(42L);
        assertThat(principal.email()).isEqualTo("ploy@example.com");
        assertThat(principal.username()).isEqualTo("ploy");
        assertThat(principal.roles()).containsExactlyInAnyOrder(RoleCode.BUYER, RoleCode.SELLER);
        assertThat(principal.hasRole(RoleCode.SELLER)).isTrue();
        assertThat(principal.hasRole(RoleCode.ADMIN)).isFalse();
    }

    @Test
    @DisplayName("drops a role name this build no longer knows")
    void ignoresUnknownRoleNames() {
        AuthPrincipal principal = AuthPrincipal.from(jwt(Map.of(
                "sub", "42",
                AuthClaims.ROLES, List.of("BUYER", "WAREHOUSE_STAFF"))));

        assertThat(principal.roles()).containsExactly(RoleCode.BUYER);
    }

    @Test
    @DisplayName("treats a token with no roles claim as having no roles")
    void handlesMissingRolesClaim() {
        AuthPrincipal principal = AuthPrincipal.from(jwt(Map.of("sub", "42")));

        assertThat(principal.roles()).isEmpty();
        assertThat(principal.hasRole(RoleCode.BUYER)).isFalse();
    }

    @Test
    @DisplayName("rejects a subject that is not a user id")
    void rejectsNonNumericSubject() {
        assertThatThrownBy(() -> AuthPrincipal.from(jwt(Map.of("sub", "ploy@example.com"))))
                .isInstanceOfSatisfying(UnauthorizedException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_TOKEN));
    }

    @Test
    @DisplayName("rejects a token with no subject at all")
    void rejectsMissingSubject() {
        assertThatThrownBy(() -> AuthPrincipal.from(jwt(Map.of(AuthClaims.EMAIL, "ploy@example.com"))))
                .isInstanceOfSatisfying(UnauthorizedException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_TOKEN));
    }

    private static Jwt jwt(Map<String, Object> claims) {
        return Jwt.withTokenValue("header.payload.signature")
                .header("alg", "HS256")
                .claims(existing -> existing.putAll(claims))
                .issuedAt(Instant.parse("2026-03-01T12:00:00Z"))
                .expiresAt(Instant.parse("2026-03-01T12:15:00Z"))
                .build();
    }
}
