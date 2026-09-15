package com.pegasus.pegasustcgapi.model;

import java.time.OffsetDateTime;

/**
 * The unit that is actually traded [RQ-5]. One card's English plain print and its
 * Japanese foil are separate rows, because they are separate markets: every
 * listing, median price and wishlist entry points here, never at the product.
 *
 * @param sku           the platform's own reference code, e.g. PKM-SV8A-025-EN-NORMAL
 * @param printingNote  free text that is part of the identity, e.g. "Alt Art"
 */
public record CatalogVariant(
        long id,
        long catalogProductId,
        String sku,
        String languageCode,
        CardFinish finish,
        CardEdition edition,
        String printingNote,
        String barcode,
        String imageUrl,
        boolean active,
        OffsetDateTime createdAt) {

    /** How a variant reads on an order line: "EN / Foil / 1st Edition". */
    public String label() {
        StringBuilder label = new StringBuilder(languageCode);
        if (finish != CardFinish.NOT_APPLICABLE) {
            label.append(" / ").append(finish);
        }
        if (edition != CardEdition.NOT_APPLICABLE) {
            label.append(" / ").append(edition);
        }
        if (printingNote != null && !printingNote.isBlank()) {
            label.append(" / ").append(printingNote);
        }
        return label.toString();
    }
}
