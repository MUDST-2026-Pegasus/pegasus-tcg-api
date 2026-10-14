package com.pegasus.pegasustcgapi.dto;

import java.util.List;

/** An ability, attack, or printed rule belonging to a catalog product. */
public record CatalogCardEffectResponse(
        String effectType,
        String name,
        String effectText,
        Integer damage,
        List<String> energyCost,
        short displayOrder,
        String sourceUrl) {
}
