package com.pegasus.pegasustcgapi.auth.dto;

/** Returned by register, login, refresh and change-password. */
public record AuthResponse(UserResponse user, TokenResponse tokens) {
}
