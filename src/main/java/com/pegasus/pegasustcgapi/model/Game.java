package com.pegasus.pegasustcgapi.model;

import java.time.OffsetDateTime;

/**
 * A card game the platform trades in [RQ-3]. Admins add one from the back office:
 * a new game is rows, never a migration.
 *
 * @param code  the stable identifier, e.g. POKEMON; it is what other systems match on
 * @param slug  the URL segment, e.g. /games/pokemon
 */
public record Game(
        short id,
        String code,
        String name,
        String nameLocal,
        String slug,
        String logoUrl,
        short displayOrder,
        boolean active,
        Long createdBy,
        OffsetDateTime createdAt) {

    /** The same game showing another logo, e.g. a stored key swapped for a readable URL. */
    public Game withLogoUrl(String url) {
        return new Game(id, code, name, nameLocal, slug, url, displayOrder, active, createdBy, createdAt);
    }
}
