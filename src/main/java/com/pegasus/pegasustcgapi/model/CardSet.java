package com.pegasus.pegasustcgapi.model;

import java.time.LocalDate;

/**
 * One release within a game — "Terastal Festival", code SV8a. Optional on a
 * catalogue entry, because an accessory belongs to no set.
 */
public record CardSet(
        int id,
        short gameId,
        String code,
        String name,
        String nameLocal,
        LocalDate releaseDate,
        Integer totalCards,
        String logoUrl) {
}
