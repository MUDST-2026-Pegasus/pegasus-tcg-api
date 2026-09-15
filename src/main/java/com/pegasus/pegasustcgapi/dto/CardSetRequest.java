package com.pegasus.pegasustcgapi.dto;

import com.pegasus.pegasustcgapi.repository.CardSetRepository.CardSetFields;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * One release within a game, e.g. SV8a "Terastal Festival".
 *
 * @param releaseDate may be in the future: a set is usually catalogued before it
 *                    is on sale, so this is not bounded to the past
 */
public record CardSetRequest(

        @NotBlank @Size(max = 32)
        @Pattern(regexp = "^[A-Za-z0-9_.-]+$",
                message = "may only contain letters, digits, dots, hyphens and underscores")
        String code,

        @NotBlank @Size(max = 150)
        String name,

        @Size(max = 150)
        String nameLocal,

        LocalDate releaseDate,

        @Positive
        Integer totalCards,

        @Size(max = 500)
        String logoUrl) {

    public CardSetFields toFields() {
        return new CardSetFields(code, name, nameLocal, releaseDate, totalCards, logoUrl);
    }
}
