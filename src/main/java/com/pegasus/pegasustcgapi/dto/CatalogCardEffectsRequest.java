package com.pegasus.pegasustcgapi.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Every ability, attack and rule of a product at once. Saving replaces what was
 * there, so reordering, adding and removing are all the same request; an empty
 * list clears the card's rules.
 */
public record CatalogCardEffectsRequest(

        @NotNull @Size(max = 30)
        List<@Valid @NotNull CatalogCardEffectRequest> effects) {
}
