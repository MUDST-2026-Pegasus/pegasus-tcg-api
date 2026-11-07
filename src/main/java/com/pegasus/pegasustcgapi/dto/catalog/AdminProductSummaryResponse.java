package com.pegasus.pegasustcgapi.dto.catalog;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

/**
 * A browse tile as an admin sees it: the public tile, and who is selling it.
 *
 * @param sellerCount         shops a buyer could take one from now
 * @param availableQuantity   cards a buyer could take now
 * @param blockedListingCount listings of it an admin has suspended
 */
public record AdminProductSummaryResponse(
        @JsonUnwrapped ProductSummaryResponse product,
        int sellerCount,
        int availableQuantity,
        int blockedListingCount) {
}
