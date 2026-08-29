package com.pegasus.pegasustcgapi.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param username matched case-insensitively; it is folded before lookup, the
 *                 same way registration folds it before storage.
 */
public record LoginRequest(

        @NotBlank
        @Size(max = 50)
        String username,

        @NotBlank
        @Size(max = 128)
        String password) {
}
