package com.pegasus.pegasustcgapi.repository;

import com.pegasus.pegasustcgapi.model.ProductType;
import java.util.Map;

/**
 * A browse request, already made safe: the attribute values are typed against
 * the game's registry, and the page size is capped.
 *
 * @param nameQuery  matched against the English and local names
 * @param attributes exact matches, typed — {@code 200} and {@code "200"} are
 *                   different things to a jsonb containment test
 * @param inStockOnly only products someone is selling right now
 */
public record ProductSearchQuery(
        Short gameId,
        Integer categoryId,
        Integer cardSetId,
        ProductType productType,
        String nameQuery,
        Map<String, Object> attributes,
        boolean activeOnly,
        boolean inStockOnly,
        Sort sort,
        int limit,
        int offset) {

    public ProductSearchQuery {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    /** What a browse page offers to order by. Price is not here: it lives on a listing. */
    public enum Sort {
        NAME,
        NEWEST,
        CARD_NUMBER
    }
}
