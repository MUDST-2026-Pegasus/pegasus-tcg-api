package com.pegasus.pegasustcgapi.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One line of the append-only stock ledger [CR-8, RQ-7].
 *
 * @param unitPublicUid the card this line is about; null for a bulk adjustment
 * @param quantityDelta positive in, negative out; ±1 whenever a card is named
 * @param unitCost      what one card cost coming in, or the average it left at
 * @param referenceType ORDER_ITEM, RETURN_ITEM or MANUAL, with {@code referenceId}
 */
public record InventoryMovement(
        long id,
        long sellerProfileId,
        Long listingId,
        Long listingUnitId,
        UUID unitPublicUid,
        long catalogVariantId,
        CardCondition condition,
        MovementType movementType,
        int quantityDelta,
        BigDecimal unitCost,
        String referenceType,
        Long referenceId,
        String note,
        Long createdBy,
        OffsetDateTime createdAt) {

    public static final String REF_ORDER_ITEM = "ORDER_ITEM";
    public static final String REF_RETURN_ITEM = "RETURN_ITEM";
}
