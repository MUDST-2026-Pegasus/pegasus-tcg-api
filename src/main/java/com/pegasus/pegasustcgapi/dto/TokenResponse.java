package com.pegasus.pegasustcgapi.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * @param accessToken       short-lived JWT; send as {@code Authorization: Bearer <token>}.
 * @param expiresIn         seconds until {@code accessToken} expires.
 * @param refreshToken      internal cookie value; never serialized into the response body.
 * @param refreshExpiresIn  seconds until {@code refreshToken} expires.
 */
@Schema(description = "JWT access token; refresh token is delivered in an HttpOnly cookie")
public record TokenResponse(
        @Schema(description = "Signed JWT access token", example = "eyJhbGciOiJIUzI1NiIsIn...")
        String accessToken,
        @Schema(description = "Token type", example = "Bearer")
        String tokenType,
        @Schema(description = "Access token lifetime in seconds", example = "900")
        long expiresIn,
        @JsonIgnore
        @Schema(hidden = true)
        String refreshToken,
        @Schema(description = "Refresh token lifetime in seconds", example = "2592000")
        long refreshExpiresIn) {

    public static TokenResponse bearer(
            String accessToken, long expiresIn, String refreshToken, long refreshExpiresIn) {
        return new TokenResponse(accessToken, "Bearer", expiresIn, refreshToken, refreshExpiresIn);
    }
}
