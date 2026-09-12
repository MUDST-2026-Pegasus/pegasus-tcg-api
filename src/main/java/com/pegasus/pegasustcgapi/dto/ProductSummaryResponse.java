package com.pegasus.pegasustcgapi.dto;

import com.pegasus.pegasustcgapi.model.CatalogProduct;
import com.pegasus.pegasustcgapi.model.ProductType;
import java.util.Map;

/**
 * One row of a browse result — enough to draw a card tile, and no more.
 *
 * @param primaryImageUrl a signed, short-lived link, minted per read because the
 *                        bucket is private; null when the entry has no art yet
 * @param variantCount    how many printings exist, so a tile can say "3 versions"
 *                        without a second request
 */
public record ProductSummaryResponse(
        long id,
        String slug,
        String name,
        String nameLocal,
        short gameId,
        int categoryId,
        Integer cardSetId,
        ProductType productType,
        String cardNumber,
        String rarityCode,
        Map<String, Object> attributes,
        String primaryImageUrl,
        int variantCount) {

    public static ProductSummaryResponse of(
            CatalogProduct product, String primaryImageUrl, int variantCount) {

        return new ProductSummaryResponse(
                product.id(),
                product.slug(),
                product.name(),
                product.nameLocal(),
                product.gameId(),
                product.categoryId(),
                product.cardSetId(),
                product.productType(),
                product.cardNumber(),
                product.rarityCode(),
                product.attributes(),
                primaryImageUrl,
                variantCount);
    }
}
