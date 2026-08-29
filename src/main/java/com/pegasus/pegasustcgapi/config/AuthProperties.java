package com.pegasus.pegasustcgapi.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Everything under {@code pegasus.auth}. Bound at startup and validated, so a
 * missing or nonsensical value fails the boot rather than the first login.
 *
 * @param jwtSecret       HS256 key, base64 or plain text, at least 32 bytes decoded.
 * @param devExposeTokens returns password reset tokens in API responses.
 *                        Local development only — never enable this in production.
 */
@Validated
@ConfigurationProperties("pegasus.auth")
public record AuthProperties(

        @NotBlank String jwtSecret,
        @NotBlank String jwtIssuer,
        @NotNull Duration accessTokenTtl,
        @NotNull Duration refreshTokenTtl,
        @NotNull Duration passwordResetTtl,
        @Min(1) int maxFailedLogins,
        @NotNull Duration lockoutDuration,
        boolean devExposeTokens) {
}
