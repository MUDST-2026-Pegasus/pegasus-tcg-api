package com.pegasus.pegasustcgapi.repository;

import static com.pegasus.pegasustcgapi.jooq.tables.InventoryMovement.INVENTORY_MOVEMENT;
import static com.pegasus.pegasustcgapi.jooq.tables.ListingUnit.LISTING_UNIT;

import com.pegasus.pegasustcgapi.jooq.tables.records.InventoryMovementRecord;
import com.pegasus.pegasustcgapi.model.CardCondition;
import com.pegasus.pegasustcgapi.model.InventoryMovement;
import com.pegasus.pegasustcgapi.model.MovementType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SelectField;
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
            Long createdBy) {
    }
}
