package com.pegasus.pegasustcgapi.dto;

import com.pegasus.pegasustcgapi.model.RoleCode;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Set;

/**
 * @param username lower-cased before storage; it becomes the public profile path {@code /u/{username}}.
 * @param roles    optional, and limited to {@link RoleCode#SELF_ASSIGNABLE}. Defaults to BUYER.
 */
public record RegisterRequest(

        @NotBlank
        @Email
        @Size(max = 255)
        String email,

        @NotBlank
        @Size(min = 3, max = 50)
        @Pattern(regexp = "^[A-Za-z0-9_]+$", message = "may only contain letters, digits and underscores")
        String username,

        @NotBlank
        @Size(max = 100)
        String displayName,

        @NotBlank
        @Size(min = 8, max = 128)
        String password,

        @Size(max = 20)
        @Pattern(regexp = "^[0-9+()\\-\\s]*$", message = "is not a valid phone number")
        String phone,

        Set<RoleCode> roles) {
}
