package com.pegasus.pegasustcgapi.dto;

import com.pegasus.pegasustcgapi.repository.GameRepository.GameFields;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * A game an admin is adding or editing [RQ-3].
 *
 * @param code  stored upper case; other systems and the seed data match on it
 * @param slug  optional. Left out, it is built from the name. It is only read when
 *              the game is created, since links point at it afterwards.
 */
public record GameRequest(

        @NotBlank @Size(max = 32)
        @Pattern(regexp = "^[A-Za-z0-9_]+$", message = "may only contain letters, digits and underscores")
        String code,

        @NotBlank @Size(max = 100)
        String name,

        @Size(max = 100)
        String nameLocal,

        @Size(max = 100)
        @Pattern(regexp = "^[a-z0-9-]*$", message = "may only contain lower case letters, digits and hyphens")
        String slug,

        @Size(max = 500)
        String logoUrl,

        Short displayOrder,

        /** Absent means active; Jackson cannot default a primitive. */
        Boolean active) {

    public GameFields toFields() {
        return new GameFields(code, name, nameLocal, slug, logoUrl,
                displayOrder == null ? 0 : displayOrder,
                active == null || active);
    }
}
