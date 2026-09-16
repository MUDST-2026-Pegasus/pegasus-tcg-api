package com.pegasus.pegasustcgapi.model;

/**
 * Singles, sealed product, accessories — the shelf a catalogue entry sits on
 * [RQ-3,4].
 *
 * @param gameId   null means the category spans every game, e.g. Accessory
 * @param parentId null at the top level; categories nest one inside another
 */
public record CatalogCategory(
        int id,
        Short gameId,
        Integer parentId,
        String code,
        String name,
        String slug,
        short displayOrder,
        boolean active) {

    /** A category with no game applies to all of them. */
    public boolean isCrossGame() {
        return gameId == null;
    }
}
