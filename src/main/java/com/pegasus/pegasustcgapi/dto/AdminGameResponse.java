package com.pegasus.pegasustcgapi.dto;

import com.pegasus.pegasustcgapi.model.Game;
import java.time.OffsetDateTime;

/**
 * A game as the back office edits it.
 *
 * @param logoUrl      the stored value — a pasted URL or an uploaded key — sent back
 *                     as is on update
 * @param logoImageUrl what a browser can open for that logo: the pasted URL, or a
 *                     short-lived read URL for the key; null when there is none
 */
public record AdminGameResponse(
        short id,
        String code,
        String name,
        String nameLocal,
        String slug,
        String logoUrl,
        String logoImageUrl,
        short displayOrder,
        boolean active,
        Long createdBy,
        OffsetDateTime createdAt) {

    public static AdminGameResponse of(Game game, String logoImageUrl) {
        return new AdminGameResponse(game.id(), game.code(), game.name(), game.nameLocal(),
                game.slug(), game.logoUrl(), logoImageUrl, game.displayOrder(), game.active(),
                game.createdBy(), game.createdAt());
    }
}
