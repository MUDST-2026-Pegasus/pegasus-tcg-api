package com.pegasus.pegasustcgapi.dto.catalog;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** @param gameId null for every game */
public record SearchAliasRequest(
        @NotBlank @Size(max = 60) String alias,
        @NotBlank @Size(max = 60) String target,
        Short gameId) {
}
