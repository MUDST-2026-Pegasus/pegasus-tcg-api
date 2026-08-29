package com.pegasus.pegasustcgapi.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of both {@code /auth/refresh} and {@code /auth/logout}. */
public record RefreshRequest(

        @NotBlank
        @Size(max = 512)
        String refreshToken) {
}
