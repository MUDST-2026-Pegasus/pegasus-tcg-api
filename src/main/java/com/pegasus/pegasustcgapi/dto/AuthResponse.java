package com.pegasus.pegasustcgapi.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Returned by register, login, refresh and change-password. */
@Schema(description = "Authentication outcome containing user details and tokens")
public record AuthResponse(
        @Schema(description = "Authenticated user profile")
        UserResponse user,
        @Schema(description = "Issued token pair")
        TokenResponse tokens) {
}
