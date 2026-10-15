package com.pegasus.pegasustcgapi.repository;

import static com.pegasus.pegasustcgapi.jooq.tables.InventoryMovement.INVENTORY_MOVEMENT;
import static com.pegasus.pegasustcgapi.jooq.tables.ListingUnit.LISTING_UNIT;

import com.pegasus.pegasustcgapi.jooq.tables.records.InventoryMovementRecord;
import com.pegasus.pegasustcgapi.model.CardCondition;
import com.pegasus.pegasustcgapi.model.InventoryMovement;
import com.pegasus.pegasustcgapi.model.MovementType;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.SelectField;
import org.jooq.SelectHavingStep;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/**
 * Reads and appends to {@code inventory_movement}, the stock ledger [CR-8].
 * Append-only: a mistake is corrected by another line, never by editing one.
 */
@Repository
public class InventoryMovementRepository {

    private final DSLContext dsl;

    public InventoryMovementRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** One JDBC batch, however many cards came in at once. */
    public void insertAll(List<NewMovement> movements) {
        if (movements.isEmpty()) {
            return;
        }
        List<InventoryMovementRecord> records = movements.stream().map(m -> {
            InventoryMovementRecord r = dsl.newRecord(INVENTORY_MOVEMENT);
            r.setSellerProfileId(m.sellerProfileId());
            r.setListingId(m.listingId());
            r.setListingUnitId(m.listingUnitId());
            r.setCatalogVariantId(m.catalogVariantId());
            r.setConditionCode(m.condition().name());
            r.setMovementType(m.type().name());
            r.setQuantityDelta(m.quantityDelta());
            r.setUnitCost(m.unitCost());
            r.setReferenceType(m.referenceType());
            r.setReferenceId(m.referenceId());
            r.setNote(m.note());
            r.setCreatedBy(m.createdBy());
            r.setStockInBatchUid(m.stockInBatchUid());
            r.setAverageCostAfter(m.averageCostAfter());
            return r;
        }).toList();

        dsl.batchInsert(records).execute();
    }

    /** Newest first, with the UUID of the card each line is about. */
    public List<InventoryMovement> findPage(MovementQuery query) {
        List<SelectField<?>> fields = new ArrayList<>(List.of(INVENTORY_MOVEMENT.fields()));
        fields.add(LISTING_UNIT.PUBLIC_UID);

        return dsl.select(fields)
                .from(INVENTORY_MOVEMENT)
                .leftJoin(LISTING_UNIT).on(LISTING_UNIT.ID.eq(INVENTORY_MOVEMENT.LISTING_UNIT_ID))
                .where(conditions(query))
                .orderBy(INVENTORY_MOVEMENT.CREATED_AT.desc(), INVENTORY_MOVEMENT.ID.desc())
                .limit(query.limit())
                .offset(query.offset())
                .fetch(InventoryMovementRepository::toMovement);
    }

    public long count(MovementQuery query) {
        return dsl.fetchCount(INVENTORY_MOVEMENT, conditions(query));
    }

    /**
     * Stock-ins of one printing and condition, a lot per row, newest first.
     *
     * <p>Lines written since TCG-502 group by their batch. Older lines have none,
     * so they group by when they were booked: a stock-in writes all its lines in one
     * transaction, and {@code now()} is the same for all of them.
     */
    public List<StockInLot> findStockIns(StockInQuery query) {
        Field<Long> firstId = DSL.min(INVENTORY_MOVEMENT.ID).as("first_id");

        return stockIns(query, firstId)
                .orderBy(INVENTORY_MOVEMENT.CREATED_AT.desc(), firstId.desc())
                .limit(query.limit())
                .offset(query.offset())
                .fetch(r -> new StockInLot(
                        r.get(firstId),
                        r.get(INVENTORY_MOVEMENT.STOCK_IN_BATCH_UID),
                        MovementType.valueOf(r.get(INVENTORY_MOVEMENT.MOVEMENT_TYPE)),
                        r.get(INVENTORY_MOVEMENT.CREATED_AT),
                        r.get(LOT_ACQUIRED_AT),
                        r.get(LOT_QUANTITY, Integer.class),
                        r.get(INVENTORY_MOVEMENT.UNIT_COST),
                        r.get(LOT_AVERAGE_AFTER),
                        r.get(LOT_NOTE),
                        r.get(INVENTORY_MOVEMENT.LISTING_ID)));
    }

    public long countStockIns(StockInQuery query) {
        return dsl.fetchCount(stockIns(query, DSL.min(INVENTORY_MOVEMENT.ID).as("first_id")));
    }

    /**
     * Cards in through stock-ins, and cards out to buyers net of any that came back,
     * for one printing and condition. Write-offs and corrections count as neither.
     */
    public StockFlow stockFlow(long sellerProfileId, long variantId, CardCondition condition) {
        Field<BigDecimal> received = DSL.sum(DSL.when(
                INVENTORY_MOVEMENT.MOVEMENT_TYPE.in(STOCK_IN_TYPES), INVENTORY_MOVEMENT.QUANTITY_DELTA)
                .otherwise(DSL.inline(0)));
        // SALE lines are negative and the undo lines positive, so their sum is minus the net sold.
        Field<BigDecimal> sold = DSL.sum(DSL.when(
                INVENTORY_MOVEMENT.MOVEMENT_TYPE.in(SALE_TYPES), INVENTORY_MOVEMENT.QUANTITY_DELTA.neg())
                .otherwise(DSL.inline(0)));

        Record record = dsl.select(received, sold)
                .from(INVENTORY_MOVEMENT)
                .where(marketOf(sellerProfileId, variantId, condition))
                .fetchSingle();
        return new StockFlow(longOrZero(record.get(received)), longOrZero(record.get(sold)));
    }

    private SelectHavingStep<? extends Record> stockIns(StockInQuery query, Field<Long> firstId) {
        return dsl.select(firstId,
                        INVENTORY_MOVEMENT.STOCK_IN_BATCH_UID,
                        INVENTORY_MOVEMENT.MOVEMENT_TYPE,
                        INVENTORY_MOVEMENT.CREATED_AT,
                        INVENTORY_MOVEMENT.UNIT_COST,
                        INVENTORY_MOVEMENT.LISTING_ID,
                        LOT_QUANTITY,
                        LOT_AVERAGE_AFTER,
                        LOT_ACQUIRED_AT,
                        LOT_NOTE)
                .from(INVENTORY_MOVEMENT)
                .leftJoin(LISTING_UNIT).on(LISTING_UNIT.ID.eq(INVENTORY_MOVEMENT.LISTING_UNIT_ID))
                .where(marketOf(query.sellerProfileId(), query.variantId(), query.condition()))
                .and(INVENTORY_MOVEMENT.MOVEMENT_TYPE.in(STOCK_IN_TYPES))
                .groupBy(INVENTORY_MOVEMENT.STOCK_IN_BATCH_UID,
                        INVENTORY_MOVEMENT.CREATED_AT,
                        INVENTORY_MOVEMENT.MOVEMENT_TYPE,
                        INVENTORY_MOVEMENT.UNIT_COST,
                        INVENTORY_MOVEMENT.LISTING_ID);
    }

    private static Condition marketOf(long sellerProfileId, long variantId, CardCondition condition) {
        return INVENTORY_MOVEMENT.SELLER_PROFILE_ID.eq(sellerProfileId)
                .and(INVENTORY_MOVEMENT.CATALOG_VARIANT_ID.eq(variantId))
                .and(INVENTORY_MOVEMENT.CONDITION_CODE.eq(condition.name()));
    }

    private static long longOrZero(BigDecimal value) {
        return value == null ? 0 : value.longValueExact();
    }

    private static final List<String> STOCK_IN_TYPES =
            List.of(MovementType.INITIAL_STOCK.name(), MovementType.RESTOCK.name());

    private static final List<String> SALE_TYPES = List.of(
            MovementType.SALE.name(), MovementType.CANCEL_RESTOCK.name(), MovementType.RETURN_RESTOCK.name());

    private static final Field<BigDecimal> LOT_QUANTITY = DSL.sum(INVENTORY_MOVEMENT.QUANTITY_DELTA).as("quantity");

    private static final Field<BigDecimal> LOT_AVERAGE_AFTER =
            DSL.max(INVENTORY_MOVEMENT.AVERAGE_COST_AFTER).as("average_cost_after");

    /** Every card of a lot was acquired at the same moment and carries the same note. */
    private static final Field<OffsetDateTime> LOT_ACQUIRED_AT = DSL.min(LISTING_UNIT.ACQUIRED_AT).as("acquired_at");

    private static final Field<String> LOT_NOTE = DSL.min(LISTING_UNIT.UNIT_NOTE).as("unit_note");

    /** What a card last left at, so a return books it back in at the same cost. */
    public Optional<BigDecimal> lastSaleCost(long unitId) {
        return dsl.select(INVENTORY_MOVEMENT.UNIT_COST)
                .from(INVENTORY_MOVEMENT)
                .where(INVENTORY_MOVEMENT.LISTING_UNIT_ID.eq(unitId))
                .and(INVENTORY_MOVEMENT.MOVEMENT_TYPE.eq(MovementType.SALE.name()))
                .and(INVENTORY_MOVEMENT.UNIT_COST.isNotNull())
                .orderBy(INVENTORY_MOVEMENT.CREATED_AT.desc(), INVENTORY_MOVEMENT.ID.desc())
                .limit(1)
                .fetchOptional(INVENTORY_MOVEMENT.UNIT_COST);
    }

    private static Condition conditions(MovementQuery query) {
        Condition condition = INVENTORY_MOVEMENT.SELLER_PROFILE_ID.eq(query.sellerProfileId());

        if (query.variantId() != null) {
            condition = condition.and(INVENTORY_MOVEMENT.CATALOG_VARIANT_ID.eq(query.variantId()));
        }
        if (query.condition() != null) {
            condition = condition.and(INVENTORY_MOVEMENT.CONDITION_CODE.eq(query.condition().name()));
        }
        if (query.type() != null) {
            condition = condition.and(INVENTORY_MOVEMENT.MOVEMENT_TYPE.eq(query.type().name()));
        }
        if (query.listingId() != null) {
            condition = condition.and(INVENTORY_MOVEMENT.LISTING_ID.eq(query.listingId()));
        }
        if (query.unitPublicUid() != null) {
            // A semi-join, so the count stays a plain read of this one table.
            condition = condition.and(INVENTORY_MOVEMENT.LISTING_UNIT_ID.in(
                    DSL.select(LISTING_UNIT.ID)
                            .from(LISTING_UNIT)
                            .where(LISTING_UNIT.PUBLIC_UID.eq(query.unitPublicUid()))
                            .and(LISTING_UNIT.SELLER_PROFILE_ID.eq(query.sellerProfileId()))));
        }
        return condition;
    }

    private static InventoryMovement toMovement(Record record) {
        InventoryMovementRecord r = record.into(INVENTORY_MOVEMENT);
        return new InventoryMovement(
                r.getId(),
                r.getSellerProfileId(),
                r.getListingId(),
                r.getListingUnitId(),
                record.get(LISTING_UNIT.PUBLIC_UID),
                r.getCatalogVariantId(),
                CardCondition.valueOf(r.getConditionCode()),
                MovementType.valueOf(r.getMovementType()),
                r.getQuantityDelta(),
                r.getUnitCost(),
                r.getReferenceType(),
                r.getReferenceId(),
                r.getNote(),
                r.getCreatedBy(),
                r.getCreatedAt());
    }

    /** @param unitPublicUid one card's whole history */
    public record MovementQuery(
            long sellerProfileId,
            Long variantId,
            CardCondition condition,
            MovementType type,
            Long listingId,
            UUID unitPublicUid,
            int limit,
            int offset) {
    }

    public record StockInQuery(
            long sellerProfileId,
            long variantId,
            CardCondition condition,
            int limit,
            int offset) {
    }

    /**
     * @param id         the lot's first ledger line; stable, so it serves as the lot's id
     * @param batchUid   null for lots booked before batches were recorded
     * @param recordedAt when the stock-in was booked
     * @param acquiredAt when the seller says the cards were acquired
     * @param averageCostAfter null for lots booked before it was recorded
     */
    public record StockInLot(
            long id,
            UUID batchUid,
            MovementType type,
            OffsetDateTime recordedAt,
            OffsetDateTime acquiredAt,
            int quantity,
            BigDecimal unitCost,
            BigDecimal averageCostAfter,
            String note,
            Long listingId) {
    }

    /** @param sold cards that left with a parcel, less any that came back */
    public record StockFlow(long received, long sold) {
    }

    /**
     * @param stockInBatchUid  shared by every line of one stock-in; null otherwise
     * @param averageCostAfter the seller's average once that stock-in landed; null otherwise
     */
    public record NewMovement(
            long sellerProfileId,
            Long listingId,
            Long listingUnitId,
            long catalogVariantId,
            CardCondition condition,
            MovementType type,
            int quantityDelta,
            BigDecimal unitCost,
            String referenceType,
            Long referenceId,
            String note,
            Long createdBy,
            UUID stockInBatchUid,
            BigDecimal averageCostAfter) {

        public NewMovement(long sellerProfileId, Long listingId, Long listingUnitId, long catalogVariantId,
                CardCondition condition, MovementType type, int quantityDelta, BigDecimal unitCost,
                String referenceType, Long referenceId, String note, Long createdBy) {
            this(sellerProfileId, listingId, listingUnitId, catalogVariantId, condition, type, quantityDelta,
                    unitCost, referenceType, referenceId, note, createdBy, null, null);
        }

        /** The same line, marked as part of one stock-in and the average it left behind. */
        public NewMovement inStockInBatch(UUID batchUid, BigDecimal averageAfter) {
            return new NewMovement(sellerProfileId, listingId, listingUnitId, catalogVariantId, condition, type,
                    quantityDelta, unitCost, referenceType, referenceId, note, createdBy, batchUid, averageAfter);
        }
    }
}
