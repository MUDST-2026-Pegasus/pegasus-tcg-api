package com.pegasus.pegasustcgapi.dto;

import com.pegasus.pegasustcgapi.model.RoleCode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Set;

/**
 * @param username lower-cased before storage; it becomes the public profile path {@code /u/{username}}.
 * @param roles    optional, and limited to {@link RoleCode#SELF_ASSIGNABLE}. Defaults to BUYER.
 */
@Schema(description = "Payload for creating a new user account")
public record RegisterRequest(

        @Schema(description = "User email address", example = "newuser@example.com", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        @Email
        @Size(max = 255)
        String email,

        @Schema(description = "Unique handle (letters, numbers, underscore)", example = "card_master_99", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        @Size(min = 3, max = 50)
        @Pattern(regexp = "^[A-Za-z0-9_]+$", message = "may only contain letters, digits and underscores")
        String username,

        @Schema(description = "User display name", example = "Card Master", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        @Size(max = 100)
        String displayName,

        @Schema(description = "Account password (min 8 characters)", example = "P@ssw0rd2026!", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        @Size(min = 8, max = 128)
        String password,

        @Schema(description = "Contact phone number", example = "+66812345678")
        @Size(max = 20)
        @Pattern(regexp = "^[0-9+()\\-\\s]*$", message = "is not a valid phone number")
        String phone,

        @Schema(description = "Initial roles to request (BUYER or SELLER)")
        Set<RoleCode> roles) {
}
