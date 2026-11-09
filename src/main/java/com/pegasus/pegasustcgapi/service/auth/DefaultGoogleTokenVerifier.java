package com.pegasus.pegasustcgapi.service.auth;

import com.pegasus.pegasustcgapi.config.AuthProperties;
import com.pegasus.pegasustcgapi.exception.ApiException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.ForbiddenException;
import com.pegasus.pegasustcgapi.exception.UnauthorizedException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.MappedJwtClaimSetConverter;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

/**
 * Verifies Google OIDC ID tokens against Google's JWK set.
 *
 * <p>Keeps its own {@link NimbusJwtDecoder} as an internal field rather than a
 * Spring {@code @Bean} so it never collides with the application's HS256
 * {@link JwtDecoder} in {@link com.pegasus.pegasustcgapi.config.SecurityConfig}.
 */
@Component
public class DefaultGoogleTokenVerifier implements GoogleTokenVerifier {

    private static final Logger log = LoggerFactory.getLogger(DefaultGoogleTokenVerifier.class);

    static final String GOOGLE_JWKS_URI = "https://www.googleapis.com/oauth2/v3/certs";

    private static final Set<String> ALLOWED_ISSUERS = Set.of(
            "https://accounts.google.com",
            "accounts.google.com");

    private final String clientId;
    private final JwtDecoder decoder;

    @Autowired
    public DefaultGoogleTokenVerifier(AuthProperties properties, Clock clock) {
        this(
                properties.googleClientId(),
                NimbusJwtDecoder.withJwkSetUri(GOOGLE_JWKS_URI).build(),
                clock);
    }

    DefaultGoogleTokenVerifier(String clientId, NimbusJwtDecoder decoder, Clock clock) {
        this.clientId = clientId == null ? "" : clientId.trim();
        if (this.clientId.isEmpty()) {
            this.decoder = null;
        } else {
            // Keep iss as a plain String so "accounts.google.com" (without scheme) is not
            // rejected by Spring Security's default String-to-URL claim converter.
            decoder.setClaimSetConverter(
                    MappedJwtClaimSetConverter.withDefaults(
                            Map.of(JwtClaimNames.ISS, claim -> claim == null ? null : claim.toString())));
            decoder.setJwtValidator(googleValidator(this.clientId, clock));
            this.decoder = decoder;
        }
    }

    @Override
    public GoogleIdentity verify(String credential) {
        if (clientId.isEmpty() || decoder == null) {
            throw new ApiException(ErrorCode.GOOGLE_SIGNIN_DISABLED);
        }
        if (credential == null || credential.isBlank()) {
            throw new UnauthorizedException(ErrorCode.GOOGLE_TOKEN_INVALID);
        }

        final Jwt jwt;
        try {
            jwt = decoder.decode(credential);
        } catch (BadJwtException e) {
            throw new UnauthorizedException(ErrorCode.GOOGLE_TOKEN_INVALID);
        } catch (JwtException e) {
            log.warn("Could not verify Google credential: {}", e.getClass().getSimpleName());
            throw new ApiException(ErrorCode.GOOGLE_SIGNIN_DISABLED, "Google sign-in is temporarily unavailable");
        }

        String subject = jwt.getSubject();
        String rawEmail = jwt.getClaimAsString("email");
        if (subject == null || subject.isBlank() || rawEmail == null || rawEmail.isBlank()) {
            throw new UnauthorizedException(ErrorCode.GOOGLE_TOKEN_INVALID);
        }

        if (!isEmailVerified(jwt)) {
            throw new ForbiddenException(ErrorCode.GOOGLE_EMAIL_NOT_VERIFIED);
        }

        String email = AuthService.normalizeEmail(rawEmail);
        String name = jwt.getClaimAsString("name");
        String hostedDomain = jwt.getClaimAsString("hd");

        return new GoogleIdentity(subject.trim(), email, true, name, hostedDomain);
    }

    private static boolean isEmailVerified(Jwt jwt) {
        return Boolean.TRUE.equals(jwt.getClaims().get("email_verified"));
    }

    private static OAuth2TokenValidator<Jwt> googleValidator(String clientId, Clock clock) {
        JwtTimestampValidator timestampValidator = new JwtTimestampValidator();
        timestampValidator.setClock(clock);

        OAuth2TokenValidator<Jwt> expRequired = token -> token.getExpiresAt() != null
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(
                        new OAuth2Error("invalid_token", "Missing exp claim", null));

        OAuth2TokenValidator<Jwt> audienceValidator = new JwtClaimValidator<List<String>>(
                JwtClaimNames.AUD, aud -> aud != null && aud.size() == 1 && clientId.equals(aud.get(0)));

        OAuth2TokenValidator<Jwt> issuerValidator = token -> {
            String iss = token.getClaimAsString(JwtClaimNames.ISS);
            if (iss != null && ALLOWED_ISSUERS.contains(iss)) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "Invalid issuer", null));
        };

        return new DelegatingOAuth2TokenValidator<>(
                expRequired, timestampValidator, audienceValidator, issuerValidator);
    }
}
