package com.pegasus.pegasustcgapi.dto;

import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.model.MovementType;
import com.pegasus.pegasustcgapi.repository.InventoryMovementRepository.StockInLot;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A seller's stock-ins of one printing and condition, a lot per row [TCG-502].
 *
 * @param receivedTotal every card that came in through a stock-in, across all pages
 * @param soldTotal     cards that left with a parcel, less any that came back
 */
public record StockInHistoryResponse(
        long receivedTotal,
        long soldTotal,
        PageResponse<Lot> lots) {

    /**
     * @param id               the lot's first ledger line
     * @param batchUid         null for lots booked before batches were recorded
     * @param receivedAt       when the seller says the cards were acquired
     * @param recordedAt       when the stock-in was booked
     * @param note             the note on the lot's cards, as the seller wrote it
     * @param averageCostAfter the average unit cost right after this lot; null for older lots
     */
    public record Lot(
            long id,
            UUID batchUid,
            MovementType movementType,
            OffsetDateTime receivedAt,
            OffsetDateTime recordedAt,
            int quantity,
            BigDecimal unitCost,
            BigDecimal totalCost,
            BigDecimal averageCostAfter,
            String note,
            Long listingId) {

        public static Lot of(StockInLot lot) {
            BigDecimal totalCost = lot.unitCost() == null
                    ? null
                    : lot.unitCost().multiply(BigDecimal.valueOf(lot.quantity())).setScale(2, RoundingMode.HALF_UP);
            return new Lot(lot.id(), lot.batchUid(), lot.type(),
                    lot.acquiredAt() != null ? lot.acquiredAt() : lot.recordedAt(), lot.recordedAt(),
                    lot.quantity(), lot.unitCost(), totalCost, lot.averageCostAfter(), lot.note(), lot.listingId());
        }
    }
}
