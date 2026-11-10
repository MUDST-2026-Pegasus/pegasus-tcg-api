package com.pegasus.pegasustcgapi.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Google ID token credential for signing in or registering with Google")
public record GoogleLoginRequest(

        @Schema(description = "Google ID token (JWT) returned by Google Identity Services",
                example = "eyJhbGciOiJSUzI1NiIsImtpZCI6...",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        @Size(max = 4096)
        String credential) {

    @Override
    public String toString() {
        return "GoogleLoginRequest[credential=REDACTED]";
    }
}
