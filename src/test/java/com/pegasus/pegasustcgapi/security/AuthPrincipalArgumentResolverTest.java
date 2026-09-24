package com.pegasus.pegasustcgapi.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.UnauthorizedException;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.context.request.ServletWebRequest;

@DisplayName("AuthPrincipalArgumentResolver")
class AuthPrincipalArgumentResolverTest {

    private final AuthPrincipalArgumentResolver resolver = new AuthPrincipalArgumentResolver();

    @AfterEach
    void cleanSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @SuppressWarnings("unused")
    private void dummyEndpoint(
            AuthPrincipal principal,
            Optional<AuthPrincipal> optionalPrincipal,
            String otherParam) {
    }

    private MethodParameter param(int index) throws NoSuchMethodException {
        Method method = getClass().getDeclaredMethod(
                "dummyEndpoint", AuthPrincipal.class, Optional.class, String.class);
        return new MethodParameter(method, index);
    }

    @Test
    @DisplayName("supports AuthPrincipal and Optional<AuthPrincipal>")
    void supportsParameter() throws Exception {
        assertThat(resolver.supportsParameter(param(0))).isTrue();
        assertThat(resolver.supportsParameter(param(1))).isTrue();
        assertThat(resolver.supportsParameter(param(2))).isFalse();
    }

    @Test
    @DisplayName("rejects an unauthenticated request for a required AuthPrincipal, cart route or not")
    void unauthenticatedCartWithRequiredPrincipalThrows() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", ApiPaths.CART);
        ServletWebRequest webRequest = new ServletWebRequest(request);

        assertThatThrownBy(() -> resolver.resolveArgument(param(0), null, webRequest, null))
                .isInstanceOfSatisfying(UnauthorizedException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED));
    }

    @Test
    @DisplayName("returns empty Optional for unauthenticated cart subpath request with Optional parameter")
    void unauthenticatedCartSubpathReturnsEmptyOptional() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", ApiPaths.CART_ITEMS);
        ServletWebRequest webRequest = new ServletWebRequest(request);

        Object result = resolver.resolveArgument(param(1), null, webRequest, null);

        assertThat(result).isEqualTo(Optional.empty());
    }

    @Test
    @DisplayName("a path that merely mentions a cart does not grant guest access")
    void cartShapedPathOnAGuardedRouteStillThrows() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", ApiPaths.ADMIN + "/carts/7/audit");
        ServletWebRequest webRequest = new ServletWebRequest(request);

        assertThatThrownBy(() -> resolver.resolveArgument(param(0), null, webRequest, null))
                .isInstanceOfSatisfying(UnauthorizedException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED));
    }

    @Test
    @DisplayName("an Optional parameter admits guests wherever it is declared")
    void optionalParameterResolvesEmptyOnAnyRoute() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", ApiPaths.COLLECTION);
        ServletWebRequest webRequest = new ServletWebRequest(request);

        Object result = resolver.resolveArgument(param(1), null, webRequest, null);

        assertThat(result).isEqualTo(Optional.empty());
    }

    @Test
    @DisplayName("throws UnauthorizedException on unauthenticated non-cart request")
    void unauthenticatedGuardedRouteThrows() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", ApiPaths.COLLECTION);
        ServletWebRequest webRequest = new ServletWebRequest(request);

        assertThatThrownBy(() -> resolver.resolveArgument(param(0), null, webRequest, null))
                .isInstanceOfSatisfying(UnauthorizedException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED));
    }

    @Test
    @DisplayName("resolves AuthPrincipal when token is present on cart route")
    void authenticatedCartRequestResolvesPrincipal() throws Exception {
        Jwt jwt = createJwt(42L, "buyer@example.com", "buyer");
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", ApiPaths.CART);
        ServletWebRequest webRequest = new ServletWebRequest(request);

        Object result = resolver.resolveArgument(param(0), null, webRequest, null);

        assertThat(result).isInstanceOf(AuthPrincipal.class);
        AuthPrincipal principal = (AuthPrincipal) result;
        assertThat(principal.userId()).isEqualTo(42L);
        assertThat(principal.email()).isEqualTo("buyer@example.com");
    }

    @Test
    @DisplayName("resolves AuthPrincipal when token is present on guarded route")
    void authenticatedGuardedRouteResolvesPrincipal() throws Exception {
        Jwt jwt = createJwt(99L, "seller@example.com", "seller");
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", ApiPaths.SELLERS_ME_ORDERS);
        ServletWebRequest webRequest = new ServletWebRequest(request);

        Object result = resolver.resolveArgument(param(0), null, webRequest, null);

        assertThat(result).isInstanceOf(AuthPrincipal.class);
        AuthPrincipal principal = (AuthPrincipal) result;
        assertThat(principal.userId()).isEqualTo(99L);
    }

    private static Jwt createJwt(Long userId, String email, String username) {
        return Jwt.withTokenValue("mock-token")
                .header("alg", "HS256")
                .claim("sub", String.valueOf(userId))
                .claim(AuthClaims.EMAIL, email)
                .claim(AuthClaims.USERNAME, username)
                .claim(AuthClaims.ROLES, List.of("BUYER"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
    }
}
