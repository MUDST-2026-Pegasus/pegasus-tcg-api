package com.pegasus.pegasustcgapi.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequest(

        @NotBlank
        @Size(max = 128)
        String currentPassword,

        @NotBlank
        @Size(min = 8, max = 128)
        String newPassword) {

    @Override
    public String toString() {
        return "ChangePasswordRequest[currentPassword=REDACTED, newPassword=REDACTED]";
    }
}
