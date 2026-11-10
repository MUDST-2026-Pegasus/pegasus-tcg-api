package com.pegasus.pegasustcgapi.service.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.pegasus.pegasustcgapi.exception.ApiException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.ForbiddenException;
import com.pegasus.pegasustcgapi.exception.UnauthorizedException;
import com.pegasus.pegasustcgapi.service.auth.GoogleTokenVerifier.GoogleIdentity;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("GoogleTokenVerifier")
class GoogleTokenVerifierTest {

    private static final String CLIENT_ID = "1234567890-pegasus.apps.googleusercontent.com";
    private static final Instant NOW = Instant.parse("2026-11-09T09:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static RSAPublicKey googlePublicKey;
    private static JwtEncoder googleEncoder;
    private static JwtEncoder attackerEncoder;

    private DefaultGoogleTokenVerifier verifier;

    @BeforeAll
    static void initKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);

        KeyPair googlePair = generator.generateKeyPair();
        googlePublicKey = (RSAPublicKey) googlePair.getPublic();
        RSAKey googleJwk = new RSAKey.Builder(googlePublicKey)
                .privateKey((RSAPrivateKey) googlePair.getPrivate())
                .keyID("google-kid-1")
                .build();
        googleEncoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(googleJwk)));

        KeyPair attackerPair = generator.generateKeyPair();
        RSAKey attackerJwk = new RSAKey.Builder((RSAPublicKey) attackerPair.getPublic())
                .privateKey((RSAPrivateKey) attackerPair.getPrivate())
                .keyID("google-kid-1")
                .build();
        attackerEncoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(attackerJwk)));
    }

    @BeforeEach
    void setUp() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(googlePublicKey)
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();
        verifier = new DefaultGoogleTokenVerifier(CLIENT_ID, decoder, CLOCK);
    }

    @Test
    @DisplayName("accepts a valid token with https://accounts.google.com issuer and normalises email")
    void acceptsHttpsIssuerAndNormalisesEmail() {
        String token = mint(googleEncoder, claims -> claims
                .claim("iss", "https://accounts.google.com")
                .claim("email", "  Ploy.User@Gmail.COM  ")
                .claim("name", "Ploy Somsak")
                .claim("hd", "company.co.th"));

        GoogleIdentity identity = verifier.verify(token);

        assertThat(identity.subject()).isEqualTo("google-sub-123");
        assertThat(identity.email()).isEqualTo("ploy.user@gmail.com");
        assertThat(identity.emailVerified()).isTrue();
        assertThat(identity.name()).isEqualTo("Ploy Somsak");
        assertThat(identity.hostedDomain()).isEqualTo("company.co.th");
        assertThat(identity.hasHostedDomain()).isTrue();
    }

    @Test
    @DisplayName("accepts a valid token with accounts.google.com issuer (without https://)")
    void acceptsBareAccountsGoogleIssuer() {
        String token = mint(googleEncoder, claims -> claims
                .claim("iss", "accounts.google.com")
                .claim("email", "user@gmail.com"));

        GoogleIdentity identity = verifier.verify(token);

        assertThat(identity.subject()).isEqualTo("google-sub-123");
        assertThat(identity.email()).isEqualTo("user@gmail.com");
        assertThat(identity.emailVerified()).isTrue();
        assertThat(identity.hasHostedDomain()).isFalse();
    }

    @Test
    @DisplayName("rejects a token signed with a wrong private key with 401 GOOGLE_TOKEN_INVALID")
    void rejectsWrongSignature() {
        String forgedToken = mint(attackerEncoder, claims -> {});

        assertThatThrownBy(() -> verifier.verify(forgedToken))
                .isInstanceOfSatisfying(UnauthorizedException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.GOOGLE_TOKEN_INVALID));
    }

    @Test
    @DisplayName("rejects an expired token with 401 GOOGLE_TOKEN_INVALID")
    void rejectsExpiredToken() {
        String expiredToken = mint(googleEncoder, claims -> claims
                .issuedAt(NOW.minus(Duration.ofHours(2)))
                .expiresAt(NOW.minus(Duration.ofMinutes(5))));

        assertThatThrownBy(() -> verifier.verify(expiredToken))
                .isInstanceOfSatisfying(UnauthorizedException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.GOOGLE_TOKEN_INVALID));
    }

    @Test
    @DisplayName("rejects a token with wrong audience with 401 GOOGLE_TOKEN_INVALID")
    void rejectsWrongAudience() {
        String wrongAudToken = mint(googleEncoder, claims -> claims
                .audience(List.of("some-other-client-id.apps.googleusercontent.com")));

        assertThatThrownBy(() -> verifier.verify(wrongAudToken))
                .isInstanceOfSatisfying(UnauthorizedException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.GOOGLE_TOKEN_INVALID));
    }

    @Test
    @DisplayName("rejects a token with multiple audiences even when one matches clientId with 401 GOOGLE_TOKEN_INVALID")
    void rejectsMultipleAudiences() {
        String multiAudToken = mint(googleEncoder, claims -> claims
                .audience(List.of(CLIENT_ID, "some-other-client-id.apps.googleusercontent.com")));

        assertThatThrownBy(() -> verifier.verify(multiAudToken))
                .isInstanceOfSatisfying(UnauthorizedException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.GOOGLE_TOKEN_INVALID));
    }

    @Test
    @DisplayName("rejects a token with wrong issuer with 401 GOOGLE_TOKEN_INVALID")
    void rejectsWrongIssuer() {
        String wrongIssToken = mint(googleEncoder, claims -> claims
                .claim("iss", "https://evil.example.com"));

        assertThatThrownBy(() -> verifier.verify(wrongIssToken))
                .isInstanceOfSatisfying(UnauthorizedException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.GOOGLE_TOKEN_INVALID));
    }

    @Test
    @DisplayName("rejects a token when email_verified is false or non-boolean with 403 GOOGLE_EMAIL_NOT_VERIFIED")
    void rejectsUnverifiedEmail() {
        String unverifiedToken = mint(googleEncoder, claims -> claims
                .claim("email_verified", false));
        String stringVerifiedToken = mint(googleEncoder, claims -> claims
                .claim("email_verified", "true"));

        assertThatThrownBy(() -> verifier.verify(unverifiedToken))
                .isInstanceOfSatisfying(ForbiddenException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.GOOGLE_EMAIL_NOT_VERIFIED));
        assertThatThrownBy(() -> verifier.verify(stringVerifiedToken))
                .isInstanceOfSatisfying(ForbiddenException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.GOOGLE_EMAIL_NOT_VERIFIED));
    }

    @Test
    @DisplayName("rejects when Google client-id is blank with 503 GOOGLE_SIGNIN_DISABLED")
    void rejectsWhenClientIdIsBlank() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(googlePublicKey)
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();
        DefaultGoogleTokenVerifier disabledVerifier = new DefaultGoogleTokenVerifier("   ", decoder, CLOCK);
        String token = mint(googleEncoder, claims -> {});

        assertThatThrownBy(() -> disabledVerifier.verify(token))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.GOOGLE_SIGNIN_DISABLED));
    }

    @Test
    @DisplayName("rejects malformed or blank credentials with 401 GOOGLE_TOKEN_INVALID")
    void rejectsMalformedCredential() {
        assertThatThrownBy(() -> verifier.verify("not-a-jwt"))
                .isInstanceOfSatisfying(UnauthorizedException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.GOOGLE_TOKEN_INVALID));
        assertThatThrownBy(() -> verifier.verify("   "))
                .isInstanceOfSatisfying(UnauthorizedException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.GOOGLE_TOKEN_INVALID));
    }

    @Test
    @DisplayName("returns 503 GOOGLE_SIGNIN_DISABLED when Google JWKS cannot be fetched (non-BadJwtException)")
    void returns503WhenJwksFetchFails() {
        NimbusJwtDecoder failingDecoder = mock(NimbusJwtDecoder.class);
        when(failingDecoder.decode("some-token"))
                .thenThrow(new JwtException("An error occurred while attempting to retrieve the JWK Set"));
        DefaultGoogleTokenVerifier unavailableVerifier =
                new DefaultGoogleTokenVerifier(CLIENT_ID, failingDecoder, CLOCK);

        assertThatThrownBy(() -> unavailableVerifier.verify("some-token"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.GOOGLE_SIGNIN_DISABLED);
                    assertThat(e.getMessage()).isEqualTo("Google sign-in is temporarily unavailable");
                });
    }

    private static String mint(JwtEncoder encoder, Consumer<JwtClaimsSet.Builder> customizer) {
        JwtClaimsSet.Builder builder = JwtClaimsSet.builder()
                .subject("google-sub-123")
                .audience(List.of(CLIENT_ID))
                .issuedAt(NOW.minusSeconds(30))
                .expiresAt(NOW.plusSeconds(3600))
                .claim("iss", "https://accounts.google.com")
                .claim("email", "ploy@gmail.com")
                .claim("email_verified", true)
                .claim("name", "Ploy");
        customizer.accept(builder);
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId("google-kid-1").build();
        return encoder.encode(JwtEncoderParameters.from(header, builder.build())).getTokenValue();
    }
}
