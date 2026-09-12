package com.pegasus.pegasustcgapi.service;

import static com.pegasus.pegasustcgapi.support.AuthUserBuilder.anActiveUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.pegasus.pegasustcgapi.config.AuthProperties;
import com.pegasus.pegasustcgapi.model.AuthUser;
import com.pegasus.pegasustcgapi.model.RoleCode;
import com.pegasus.pegasustcgapi.security.AuthClaims;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Signs with a real encoder and reads the result back with a real decoder, so the
 * assertions are about the token a client will actually receive rather than about
 * the builder calls that produced it.
 */
@DisplayName("JwtService")
class JwtServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");
    private static final Duration ACCESS_TTL = Duration.ofMinutes(15);
    private static final String ISSUER = "pegasus-tcg-api";

    private static final SecretKey KEY = new SecretKeySpec(
            "unit-test-signing-key-of-at-least-32-bytes".getBytes(StandardCharsets.UTF_8), "HmacSHA256");

    private JwtService service;
    private JwtDecoder decoder;

    @BeforeEach
    void setUp() {
        AuthProperties properties = new AuthProperties(
                "unused-here", ISSUER, ACCESS_TTL, Duration.ofDays(30), Duration.ofHours(1),
                5, Duration.ofMinutes(15), false);

        service = new JwtService(
                new NimbusJwtEncoder(new ImmutableSecret<>(KEY)), properties, Clock.fixed(NOW, ZoneOffset.UTC));

        NimbusJwtDecoder nimbus = NimbusJwtDecoder.withSecretKey(KEY).macAlgorithm(MacAlgorithm.HS256).build();
        // The service's clock is fixed in the past; only the signature is under test here,
        // so skip the timestamp validator the resource server would apply.
        nimbus.setJwtValidator(token -> OAuth2TokenValidatorResult.success());
        decoder = nimbus;
    }

    @Test
    @DisplayName("mints an HS256 token carrying the identity and roles")
    void issuesSignedAccessToken() {
        AuthUser user = anActiveUser().withId(42L)
                .withEmail("ploy@example.com")
                .withUsername("ploy")
                .withRoles(RoleCode.BUYER, RoleCode.SELLER)
                .build();

        JwtService.AccessToken issued = service.issue(user);
        Jwt decoded = decoder.decode(issued.value());

        assertThat(decoded.getHeaders()).containsEntry("alg", MacAlgorithm.HS256.getName());
        assertThat(decoded.getSubject()).isEqualTo("42");
        assertThat(decoded.getClaimAsString("iss")).isEqualTo(ISSUER);
        assertThat(decoded.getClaimAsString(AuthClaims.EMAIL)).isEqualTo("ploy@example.com");
        assertThat(decoded.getClaimAsString(AuthClaims.USERNAME)).isEqualTo("ploy");
        assertThat(decoded.getClaimAsStringList(AuthClaims.ROLES))
                .containsExactlyInAnyOrder("BUYER", "SELLER");
        assertThat(decoded.getClaimAsString(AuthClaims.TOKEN_TYPE))
                .isEqualTo(AuthClaims.ACCESS_TOKEN_TYPE);
    }

    @Test
    @DisplayName("expires one access-token TTL after the clock's now")
    void appliesConfiguredTtl() {
        JwtService.AccessToken issued = service.issue(anActiveUser().build());
        Jwt decoded = decoder.decode(issued.value());

        assertThat(decoded.getIssuedAt()).isEqualTo(NOW);
        assertThat(decoded.getExpiresAt()).isEqualTo(NOW.plus(ACCESS_TTL));
        assertThat(issued.expiresInSeconds()).isEqualTo(ACCESS_TTL.toSeconds());
    }

    @Test
    @DisplayName("gives every token its own id, so one can be traced or denied")
    void givesEachTokenAUniqueId() {
        AuthUser user = anActiveUser().build();

        String first = decoder.decode(service.issue(user).value()).getId();
        String second = decoder.decode(service.issue(user).value()).getId();

        assertThat(first).isNotBlank().isNotEqualTo(second);
    }

    @Test
    @DisplayName("writes an empty roles claim for a user loaded without roles")
    void handlesUserWithoutRoles() {
        AuthUser user = anActiveUser().withRoles().build();

        Jwt decoded = decoder.decode(service.issue(user).value());

        assertThat(decoded.getClaimAsStringList(AuthClaims.ROLES)).isEmpty();
    }

    @Test
    @DisplayName("refuses a token signed with a different key")
    void rejectsForeignSignature() {
        SecretKey otherKey = new SecretKeySpec(
                "a-completely-different-signing-key-32b".getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        NimbusJwtDecoder foreign = NimbusJwtDecoder.withSecretKey(otherKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();

        String token = service.issue(anActiveUser().build()).value();

        assertThatThrownBy(() -> foreign.decode(token)).isInstanceOf(JwtException.class);
    }
}
