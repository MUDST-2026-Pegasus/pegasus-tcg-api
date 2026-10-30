package com.pegasus.pegasustcgapi.dto;

import com.pegasus.pegasustcgapi.model.CardSet;
import java.time.LocalDate;

/**
 * A card set, for the public filters and the back office alike.
 *
 * @param logoUrl      the stored value — a pasted URL or an uploaded key — which the
 *                     back office sends back as is on update
 * @param logoImageUrl what a browser can open for that logo: the pasted URL, or a
 *                     short-lived read URL for the key; null when there is none
 */
public record CardSetResponse(
        int id,
        short gameId,
        String code,
        String name,
        String nameLocal,
        LocalDate releaseDate,
        Integer totalCards,
        String logoUrl,
        String logoImageUrl) {

    public static CardSetResponse of(CardSet cardSet, String logoImageUrl) {
        return new CardSetResponse(cardSet.id(), cardSet.gameId(), cardSet.code(), cardSet.name(),
                cardSet.nameLocal(), cardSet.releaseDate(), cardSet.totalCards(), cardSet.logoUrl(),
                logoImageUrl);
    }
}
