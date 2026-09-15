package com.pegasus.pegasustcgapi.dto;

import com.pegasus.pegasustcgapi.repository.CollectionItemRepository.Summary;
import java.math.BigDecimal;

/**
 * The owner's collection at a glance.
 *
 * @param items            rows, which is not the same as cards: one row can hold three
 * @param cards            the sum of every row's quantity
 * @param distinctVariants how many different printings
 * @param acquiredValue    what the owner recorded paying, in total. Rows without a
 *                         price count as nothing, so this is a floor, not a valuation.
 */
public record CollectionSummaryResponse(
        int items,
        long cards,
        int distinctVariants,
        long publicCards,
        BigDecimal acquiredValue) {

    public static CollectionSummaryResponse from(Summary summary) {
        return new CollectionSummaryResponse(summary.items(), summary.cards(),
                summary.distinctVariants(), summary.publicCards(), summary.acquiredValue());
    }
}
