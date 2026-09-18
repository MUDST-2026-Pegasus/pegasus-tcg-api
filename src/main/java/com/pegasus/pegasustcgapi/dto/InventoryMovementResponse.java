package com.pegasus.pegasustcgapi.dto;

import com.pegasus.pegasustcgapi.model.CardCondition;
import com.pegasus.pegasustcgapi.model.InventoryMovement;
import com.pegasus.pegasustcgapi.model.MovementType;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A line of the stock ledger.
 *
 * @param unitPublicUid the card; filter by it to read one card's whole history
 * @param createdBy     null when the system booked it, e.g. a sale shipped by the order flow
 */
public record InventoryMovementResponse(
        long id,
        MovementType movementType,
        int quantityDelta,
        BigDecimal unitCost,
        Long listingId,
        UUID unitPublicUid,
        ListingCard card,
        CardCondition condition,
        String referenceType,
        Long referenceId,
        String note,
        Long createdBy,
        OffsetDateTime createdAt) {

    public static InventoryMovementResponse of(InventoryMovement m, ListingCard card) {
        return new InventoryMovementResponse(m.id(), m.movementType(), m.quantityDelta(), m.unitCost(),
                m.listingId(), m.unitPublicUid(), card, m.condition(), m.referenceType(), m.referenceId(),
                m.note(), m.createdBy(), m.createdAt());
    }
}
