package com.pegasus.pegasustcgapi.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param email matched case-insensitively; it is folded before lookup, the same
 *              way registration folds it before storage.
 */
public record LoginRequest(

        @NotBlank
        @Size(max = 255)
        String email,

        @NotBlank
        @Size(max = 128)
        String password) {
}
