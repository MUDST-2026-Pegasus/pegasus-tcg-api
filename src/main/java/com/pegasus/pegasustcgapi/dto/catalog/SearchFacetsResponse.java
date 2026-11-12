package com.pegasus.pegasustcgapi.dto.catalog;

import java.util.List;

/**
 * Sidebar counts and tab totals for the search page (TCG-775). Each group is
 * counted without its own filter applied.
 */
public record SearchFacetsResponse(
        List<Option> games,
        List<Option> categories,
        List<ConditionCount> conditions,
        long onSaleCount,
        Totals totals) {

    /** @param parentId categories only */
    public record Option(Long id, String name, Long parentId, int count) {
    }

    public record ConditionCount(String code, int count) {
    }

    /** @param products equals totalItems of /catalog/products with the same parameters */
    public record Totals(long products, int shops, int listings) {
    }
}
