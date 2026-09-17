package com.pegasus.pegasustcgapi.service;

import com.pegasus.pegasustcgapi.dto.ListingCard;
import com.pegasus.pegasustcgapi.model.CatalogProduct;
import com.pegasus.pegasustcgapi.model.CatalogVariant;
import com.pegasus.pegasustcgapi.repository.CatalogImageRepository;
import com.pegasus.pegasustcgapi.repository.CatalogProductRepository;
import com.pegasus.pegasustcgapi.repository.CatalogVariantRepository;
import com.pegasus.pegasustcgapi.storage.StorageService;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Attaches the card to listings, stock and ledger lines: one query for the
 * variants, one for the products and one for the art, however many rows a page has.
 */
@Component
public class ListingCardRenderer {

    private final CatalogVariantRepository variants;
    private final CatalogProductRepository products;
    private final CatalogImageRepository catalogImages;
    private final StorageService storage;

    public ListingCardRenderer(
            CatalogVariantRepository variants,
            CatalogProductRepository products,
            CatalogImageRepository catalogImages,
            StorageService storage) {

        this.variants = variants;
        this.products = products;
        this.catalogImages = catalogImages;
        this.storage = storage;
    }

    /** Keyed by variant id. */
    public Map<Long, ListingCard> cards(Collection<Long> variantIds) {
        if (variantIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, CatalogVariant> variantsById = variants.findByIds(variantIds.stream().distinct().toList());

        List<Long> productIds = variantsById.values().stream()
                .map(CatalogVariant::catalogProductId).distinct().toList();
        Map<Long, CatalogProduct> productsById = products.findByIds(productIds);
        Map<Long, String> artByProduct = catalogImages.primaryKeysOf(productIds);

        Map<Long, ListingCard> cards = new HashMap<>();
        variantsById.forEach((id, variant) -> {
            CatalogProduct product = productsById.get(variant.catalogProductId());
            cards.put(id, ListingCard.of(variant, product, signed(artByProduct.get(product.id()))));
        });
        return cards;
    }

    public ListingCard card(long variantId) {
        return cards(List.of(variantId)).get(variantId);
    }

    /** A short-lived read URL; null in, null out. */
    public String signed(String imageKey) {
        return imageKey == null ? null : storage.presignDownload(imageKey);
    }
}
