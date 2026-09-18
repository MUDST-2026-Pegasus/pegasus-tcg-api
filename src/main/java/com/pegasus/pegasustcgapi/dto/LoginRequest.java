package com.pegasus.pegasustcgapi.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param email matched case-insensitively; it is folded before lookup, the same
 *              way registration folds it before storage.
 */
@Schema(description = "Credentials for authenticating a user")
public record LoginRequest(

        @Schema(description = "User email address", example = "user@example.com", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        @Size(max = 255)
        String email,

        @Schema(description = "User password", example = "SecretPass123!", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        @Size(max = 128)
        String password) {
}
