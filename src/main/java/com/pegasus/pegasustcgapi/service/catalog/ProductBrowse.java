package com.pegasus.pegasustcgapi.service.catalog;

import com.pegasus.pegasustcgapi.model.catalog.AdminSaleStatus;
import com.pegasus.pegasustcgapi.model.catalog.CardCondition;
import com.pegasus.pegasustcgapi.model.catalog.ProductType;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

/**
 * A browse request as it arrives, before {@link CatalogSearchService} checks and
 * types it.
 *
 * @param gameIds       empty for every game; attribute filters need exactly one
 * @param rawParameters every query parameter, for the {@code attr.*} ones
 * @param activeOnly    true for the public catalogue, false for an admin
 * @param inStockOnly   true to leave out cards nobody is selling right now
 * @param conditions    only cards on sale in one of these; empty for any
 * @param minPrice      THB, against the cheapest matching listing; null for no floor
 * @param maxPrice      same, for the ceiling
 * @param saleStatus    an admin's filter on who sells it; null for no filter
 */
public record ProductBrowse(
        Set<Short> gameIds,
        Integer categoryId,
        Integer cardSetId,
        ProductType productType,
        String nameQuery,
        Map<String, String> rawParameters,
        String sort,
        boolean activeOnly,
        boolean inStockOnly,
        Set<CardCondition> conditions,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        int page,
        int size,
        AdminSaleStatus saleStatus) {

    public ProductBrowse(
            Set<Short> gameIds,
            Integer categoryId,
            Integer cardSetId,
            ProductType productType,
            String nameQuery,
            Map<String, String> rawParameters,
            String sort,
            boolean activeOnly,
            boolean inStockOnly,
            Set<CardCondition> conditions,
            BigDecimal minPrice,
            BigDecimal maxPrice,
            int page,
            int size) {
        this(gameIds, categoryId, cardSetId, productType, nameQuery, rawParameters, sort, activeOnly, inStockOnly,
                conditions, minPrice, maxPrice, page, size, null);
    }

    public ProductBrowse {
        gameIds = gameIds == null ? Set.of() : Set.copyOf(gameIds);
        rawParameters = rawParameters == null ? Map.of() : rawParameters;
        conditions = conditions == null ? Set.of() : Set.copyOf(conditions);
    }

    /** The public catalogue, every game, nothing narrowed: a starting point for tests and callers. */
    public static ProductBrowse everything(int page, int size) {
        return new ProductBrowse(Set.of(), null, null, null, null, Map.of(), null,
                true, false, Set.of(), null, null, page, size);
    }
}
