package com.pegasus.pegasustcgapi.dto;

/**
 * @param accessToken       short-lived JWT; send as {@code Authorization: Bearer <token>}.
 * @param expiresIn         seconds until {@code accessToken} expires.
 * @param refreshToken      opaque, single-use. Refreshing rotates it, so store the new one.
 * @param refreshExpiresIn  seconds until {@code refreshToken} expires.
 */
public record TokenResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        String refreshToken,
        long refreshExpiresIn) {

    public static TokenResponse bearer(
            String accessToken, long expiresIn, String refreshToken, long refreshExpiresIn) {
        return new TokenResponse(accessToken, "Bearer", expiresIn, refreshToken, refreshExpiresIn);
    }
}
