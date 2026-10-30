package com.pegasus.pegasustcgapi.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Returned by register, login, refresh and change-password. */
@Schema(description = "Authentication outcome containing user details and access token; refresh token is sent in an HttpOnly cookie")
public record AuthResponse(
        @Schema(description = "Authenticated user profile")
        UserResponse user,
        @Schema(description = "Issued access token and token lifetimes")
        TokenResponse tokens) {
}
