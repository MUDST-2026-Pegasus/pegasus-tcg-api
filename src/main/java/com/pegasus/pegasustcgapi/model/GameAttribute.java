package com.pegasus.pegasustcgapi.model;

import java.util.List;

/**
 * One field a game's cards have — Pokémon HP, Magic mana cost. This is the
 * registry that makes multi-game work: it declares the field, and
 * {@code catalog_product.attributes} holds the value, so supporting a new game
 * is data rather than DDL [RQ-3].
 *
 * @param attrKey    the key used inside {@code catalog_product.attributes}
 * @param options    the choice list; only an {@link AttributeDataType#ENUM} has one
 * @param filterable whether it appears in the browse sidebar
 */
public record GameAttribute(
        int id,
        short gameId,
        String attrKey,
        String label,
        AttributeDataType dataType,
        List<String> options,
        boolean filterable,
        boolean required,
        short displayOrder) {

    public GameAttribute {
        options = options == null ? List.of() : List.copyOf(options);
    }
}
