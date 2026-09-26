package com.pegasus.pegasustcgapi.security;

import static com.pegasus.pegasustcgapi.support.AuthUserBuilder.anActiveUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.pegasus.pegasustcgapi.config.AuthProperties;
import com.pegasus.pegasustcgapi.model.AuthUser;
import com.pegasus.pegasustcgapi.model.RoleCode;
import com.pegasus.pegasustcgapi.service.JwtService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Unit Test for JWT Security & Validation (TCG-389).
 * Verifies token expiration, invalid signatures, malformed tokens, and tamper detection.
 */
@DisplayName("JwtTokenProvider Security Tests")
class JwtTokenProviderTest {

    private static final String ISSUER = "pegasus-tcg-api";
    private static final Duration ACCESS_TTL = Duration.ofMinutes(15);
    private static final Instant BASE_TIME = Instant.parse("2026-03-01T12:00:00Z");

    private static final SecretKey PRIMARY_KEY = new SecretKeySpec(
            "unit-test-secret-key-must-be-at-least-32-bytes-long".getBytes(StandardCharsets.UTF_8), "HmacSHA256");

    private static final SecretKey ATTACKER_KEY = new SecretKeySpec(
            "attacker-fake-secret-key-also-needs-32-bytes-here".getBytes(StandardCharsets.UTF_8), "HmacSHA256");

    private AuthProperties properties;

    @BeforeEach
    void setUp() {
        properties = new AuthProperties(
                "dummy", ISSUER, ACCESS_TTL, Duration.ofDays(30), Duration.ofHours(1),
                5, Duration.ofMinutes(15), false);
    }

    private JwtService createJwtService(Clock clock, SecretKey key) {
        return new JwtService(new NimbusJwtEncoder(new ImmutableSecret<>(key)), properties, clock);
    }

    private NimbusJwtDecoder createDecoder(SecretKey key, Clock clock) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        JwtTimestampValidator timestampValidator = new JwtTimestampValidator(Duration.ZERO);
        timestampValidator.setClock(clock);
        decoder.setJwtValidator(timestampValidator);
        return decoder;
    }

    @Test
    @DisplayName("Positive: Valid token is correctly signed, parses claims and passes validation")
    void validateValidTokenSucceeds() {
        Clock clock = Clock.fixed(BASE_TIME, ZoneOffset.UTC);
        JwtService service = createJwtService(clock, PRIMARY_KEY);
        NimbusJwtDecoder decoder = createDecoder(PRIMARY_KEY, clock);

        AuthUser user = anActiveUser().withId(777L)
                .withEmail("security@example.com")
                .withUsername("security_tester")
                .withRoles(RoleCode.BUYER)
                .build();

        String token = service.issue(user).value();
        Jwt jwt = decoder.decode(token);

        assertThat(jwt.getSubject()).isEqualTo("777");
        assertThat(jwt.getClaimAsString(AuthClaims.EMAIL)).isEqualTo("security@example.com");
        assertThat(jwt.getClaimAsString(AuthClaims.USERNAME)).isEqualTo("security_tester");
        assertThat(jwt.getClaimAsStringList(AuthClaims.ROLES)).containsExactly("BUYER");
    }

    @Test
    @DisplayName("Negative: Token validated after expiration timestamp throws JwtValidationException")
    void validateExpiredTokenThrowsValidationException() {
        Clock mintClock = Clock.fixed(BASE_TIME, ZoneOffset.UTC);
        JwtService service = createJwtService(mintClock, PRIMARY_KEY);
        String token = service.issue(anActiveUser().build()).value();

        // Advance clock by 16 minutes (TTL is 15 minutes)
        Clock futureClock = Clock.fixed(BASE_TIME.plus(Duration.ofMinutes(16)), ZoneOffset.UTC);
        NimbusJwtDecoder futureDecoder = createDecoder(PRIMARY_KEY, futureClock);

        assertThatThrownBy(() -> futureDecoder.decode(token))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("expired");
    }

    @Test
    @DisplayName("Negative: Token signed with unauthorized/attacker secret throws JwtException")
    void validateForeignSignatureThrowsJwtException() {
        Clock clock = Clock.fixed(BASE_TIME, ZoneOffset.UTC);
        // Mint with attacker key
        JwtService attackerService = createJwtService(clock, ATTACKER_KEY);
        String forgedToken = attackerService.issue(anActiveUser().build()).value();

        // Verify with system key
        NimbusJwtDecoder systemDecoder = createDecoder(PRIMARY_KEY, clock);

        assertThatThrownBy(() -> systemDecoder.decode(forgedToken))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("Negative: Malformed token string throws JwtException")
    void validateMalformedTokenThrowsJwtException() {
        Clock clock = Clock.fixed(BASE_TIME, ZoneOffset.UTC);
        NimbusJwtDecoder decoder = createDecoder(PRIMARY_KEY, clock);

        assertThatThrownBy(() -> decoder.decode("this.is.not.a.valid.jwt.token"))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("Negative: Tampered payload bytes invalidate HMAC signature and throw JwtException")
    void validateTamperedPayloadThrowsJwtException() {
        Clock clock = Clock.fixed(BASE_TIME, ZoneOffset.UTC);
        JwtService service = createJwtService(clock, PRIMARY_KEY);
        NimbusJwtDecoder decoder = createDecoder(PRIMARY_KEY, clock);

        String originalToken = service.issue(anActiveUser().build()).value();
        String[] parts = originalToken.split("\\.");
        assertThat(parts).hasSize(3);

        // Tamper with payload (modify a byte in the middle)
        String tamperedPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"sub\":\"99999\",\"tampered\":true}".getBytes(StandardCharsets.UTF_8));
        String tamperedToken = parts[0] + "." + tamperedPayload + "." + parts[2];

        assertThatThrownBy(() -> decoder.decode(tamperedToken))
                .isInstanceOf(JwtException.class);
    }
}
