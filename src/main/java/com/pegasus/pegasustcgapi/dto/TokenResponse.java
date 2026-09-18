package com.pegasus.pegasustcgapi.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * @param accessToken       short-lived JWT; send as {@code Authorization: Bearer <token>}.
 * @param expiresIn         seconds until {@code accessToken} expires.
 * @param refreshToken      opaque, single-use. Refreshing rotates it, so store the new one.
 * @param refreshExpiresIn  seconds until {@code refreshToken} expires.
 */
@Schema(description = "JWT access and refresh token pair")
public record TokenResponse(
        @Schema(description = "Signed JWT access token", example = "eyJhbGciOiJIUzI1NiIsIn...")
        String accessToken,
        @Schema(description = "Token type", example = "Bearer")
        String tokenType,
        @Schema(description = "Access token lifetime in seconds", example = "900")
        long expiresIn,
        @Schema(description = "Opaque refresh token string", example = "q8wEv3X5x7kL1pZ8n6tY4r9m0bV3c8d1e2f3g4h5i6")
        String refreshToken,
        @Schema(description = "Refresh token lifetime in seconds", example = "2592000")
        long refreshExpiresIn) {

    public static TokenResponse bearer(
            String accessToken, long expiresIn, String refreshToken, long refreshExpiresIn) {
        return new TokenResponse(accessToken, "Bearer", expiresIn, refreshToken, refreshExpiresIn);
    }
}
