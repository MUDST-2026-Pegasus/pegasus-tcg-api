package com.pegasus.pegasustcgapi.repository;

import com.pegasus.pegasustcgapi.model.CardCondition;
import com.pegasus.pegasustcgapi.model.ProductType;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

/**
 * A browse request, already made safe: the attribute values are typed against
 * the game's registry, and the page size is capped.
 *
 * @param gameIds     empty for every game
 * @param categoryId  also matches the categories filed under it, so Sealed
 *                    finds the booster boxes on its child shelf
 * @param nameQuery   matched against the English and local names and the card number
 * @param attributes  exact matches, typed — {@code 200} and {@code "200"} are
 *                    different things to a jsonb containment test
 * @param inStockOnly only products someone is selling right now
 * @param conditions  only products on sale in one of these conditions; it also
 *                    narrows which listings the price filter and sort look at
 * @param minPrice    against the cheapest matching listing on sale; null for no floor
 * @param maxPrice    same, for the ceiling
 */
public record ProductSearchQuery(
        Set<Short> gameIds,
        Integer categoryId,
        Integer cardSetId,
        ProductType productType,
        String nameQuery,
        Map<String, Object> attributes,
        boolean activeOnly,
        boolean inStockOnly,
        Set<CardCondition> conditions,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        Sort sort,
        int limit,
        int offset) {

    public ProductSearchQuery {
        gameIds = gameIds == null ? Set.of() : Set.copyOf(gameIds);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        conditions = conditions == null ? Set.of() : Set.copyOf(conditions);
    }

    /** Any filter that only a product on sale can pass. */
    public boolean needsOffer() {
        return inStockOnly || !conditions.isEmpty() || minPrice != null || maxPrice != null;
    }

    /**
     * What a browse page offers to order by. The price sorts go by the cheapest
     * matching listing on sale, with cards nobody is selling last either way.
     */
    public enum Sort {
        NAME,
        NEWEST,
        CARD_NUMBER,
        PRICE_ASC,
        PRICE_DESC,
        /** Most listings on sale first: what buyers are finding plenty of. */
        POPULAR
    }
}
