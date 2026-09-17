package com.pegasus.pegasustcgapi.repository;

import static com.pegasus.pegasustcgapi.jooq.tables.Listing.LISTING;
import static com.pegasus.pegasustcgapi.jooq.tables.ListingUnit.LISTING_UNIT;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerProfile.SELLER_PROFILE;
import static com.pegasus.pegasustcgapi.jooq.tables.UserAccount.USER_ACCOUNT;

import com.pegasus.pegasustcgapi.jooq.tables.records.ListingUnitRecord;
import com.pegasus.pegasustcgapi.model.CardCondition;
import com.pegasus.pegasustcgapi.model.ListingStatus;
import com.pegasus.pegasustcgapi.model.ListingUnit;
import com.pegasus.pegasustcgapi.model.ListingUnitStatus;
import com.pegasus.pegasustcgapi.model.SellerStatus;
import com.pegasus.pegasustcgapi.model.UserStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.InsertValuesStep9;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/**
 * Reads and writes {@code listing_unit}, one row per physical card.
 *
 * <p>Every write here fires two triggers. One refuses a card joining a listing
 * of another printing, condition or seller. The other recounts the listing's
 * {@code quantity_*} — and takes that listing's row lock to do it. That is why
 * cards are always locked before their listing in this module, never after: a
 * transaction that held a listing and then waited for one of its cards would
 * deadlock against a checkout holding the card and waiting for the listing.
 */
@Repository
public class ListingUnitRepository {

    private static final List<String> SELLER_MOVABLE = List.of(
            ListingUnitStatus.IN_STOCK.name(), ListingUnitStatus.LISTED.name(), ListingUnitStatus.RETURNED.name());

    private final DSLContext dsl;

    public ListingUnitRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    // ---------- reads ----------

    /** Scoped to the owner, so another seller's card reads as not found. */
    public Optional<ListingUnit> findOfSeller(UUID publicUid, long sellerProfileId) {
        return dsl.selectFrom(LISTING_UNIT)
                .where(LISTING_UNIT.PUBLIC_UID.eq(publicUid))
                .and(LISTING_UNIT.SELLER_PROFILE_ID.eq(sellerProfileId))
                .and(LISTING_UNIT.DELETED_AT.isNull())
                .fetchOptional()
                .map(ListingUnitRepository::toUnit);
    }

    /** Same, held to the end of the transaction. */
    public Optional<ListingUnit> lockOfSeller(UUID publicUid, long sellerProfileId) {
        return dsl.selectFrom(LISTING_UNIT)
                .where(LISTING_UNIT.PUBLIC_UID.eq(publicUid))
                .and(LISTING_UNIT.SELLER_PROFILE_ID.eq(sellerProfileId))
                .and(LISTING_UNIT.DELETED_AT.isNull())
                .forUpdate()
                .fetchOptional()
                .map(ListingUnitRepository::toUnit);
    }

    public List<ListingUnit> findPage(UnitQuery query) {
        return dsl.selectFrom(LISTING_UNIT)
                .where(conditions(query))
                .orderBy(LISTING_UNIT.CREATED_AT.desc(), LISTING_UNIT.ID.desc())
                .limit(query.limit())
                .offset(query.offset())
                .fetch(ListingUnitRepository::toUnit);
    }

    public long count(UnitQuery query) {
        return dsl.fetchCount(LISTING_UNIT, conditions(query));
    }

    // ---------- the seller's moves ----------

    /** One row per card, each with its own UUID. On a listing they arrive LISTED, otherwise IN_STOCK. */
    public List<ListingUnit> insert(NewUnits units) {
        String status = (units.listingId() == null ? ListingUnitStatus.IN_STOCK : ListingUnitStatus.LISTED).name();

        InsertValuesStep9<ListingUnitRecord, Long, Long, String, Long, String, String, BigDecimal,
                OffsetDateTime, String> insert = dsl.insertInto(LISTING_UNIT,
                LISTING_UNIT.SELLER_PROFILE_ID, LISTING_UNIT.CATALOG_VARIANT_ID, LISTING_UNIT.CONDITION_CODE,
                LISTING_UNIT.LISTING_ID, LISTING_UNIT.STATUS, LISTING_UNIT.CERT_NUMBER,
                LISTING_UNIT.ACQUISITION_COST, LISTING_UNIT.ACQUIRED_AT, LISTING_UNIT.UNIT_NOTE);

        for (int i = 0; i < units.quantity(); i++) {
            insert = insert.values(units.sellerProfileId(), units.catalogVariantId(), units.condition().name(),
                    units.listingId(), status, units.certNumber(), units.unitCost(), units.acquiredAt(),
                    units.unitNote());
        }
        return insert.returning().fetch().map(ListingUnitRepository::toUnit);
    }

    /**
     * Puts a card on a listing, or back in the seller's hands when
     * {@code listingId} is null. A RETURNED card that the seller moves is thereby
     * accepted back into sellable stock.
     *
     * @return false when the card was reserved, sold or written off in the meantime
     */
    public boolean place(long unitId, Long listingId) {
        ListingUnitStatus status = listingId == null ? ListingUnitStatus.IN_STOCK : ListingUnitStatus.LISTED;
        return dsl.update(LISTING_UNIT)
                .set(LISTING_UNIT.LISTING_ID, listingId)
                .set(LISTING_UNIT.STATUS, status.name())
                .where(LISTING_UNIT.ID.eq(unitId))
                .and(LISTING_UNIT.STATUS.in(SELLER_MOVABLE))
                .and(LISTING_UNIT.DELETED_AT.isNull())
                .execute() > 0;
    }

    /** The cards on sale on a listing, locked in id order before the listing itself is touched. */
    public List<ListingUnit> lockListedOf(long listingId) {
        return dsl.selectFrom(LISTING_UNIT)
                .where(LISTING_UNIT.LISTING_ID.eq(listingId))
                .and(LISTING_UNIT.STATUS.eq(ListingUnitStatus.LISTED.name()))
                .and(LISTING_UNIT.DELETED_AT.isNull())
                .orderBy(LISTING_UNIT.ID)
                .forUpdate()
                .fetch(ListingUnitRepository::toUnit);
    }

    /** Takes cards off their listing and back into the seller's hands. Only LISTED ones move. */
    public int detach(Collection<Long> unitIds) {
        if (unitIds.isEmpty()) {
            return 0;
        }
        return dsl.update(LISTING_UNIT)
                .set(LISTING_UNIT.LISTING_ID, (Long) null)
                .set(LISTING_UNIT.STATUS, ListingUnitStatus.IN_STOCK.name())
                .where(LISTING_UNIT.ID.in(unitIds))
                .and(LISTING_UNIT.STATUS.eq(ListingUnitStatus.LISTED.name()))
                .execute();
    }

    /** The cost is not here on purpose: the ledger and the average were booked from it. */
    public boolean updateNotes(long unitId, String certNumber, String unitNote, OffsetDateTime acquiredAt) {
        return dsl.update(LISTING_UNIT)
                .set(LISTING_UNIT.CERT_NUMBER, certNumber)
                .set(LISTING_UNIT.UNIT_NOTE, unitNote)
                .set(LISTING_UNIT.ACQUIRED_AT, acquiredAt)
                .where(LISTING_UNIT.ID.eq(unitId))
                .and(LISTING_UNIT.DELETED_AT.isNull())
                .execute() > 0;
    }

    /** @return false when the card was reserved or sold in the meantime */
    public boolean writeOff(long unitId) {
        return dsl.update(LISTING_UNIT)
                .set(LISTING_UNIT.LISTING_ID, (Long) null)
                .set(LISTING_UNIT.STATUS, ListingUnitStatus.WRITTEN_OFF.name())
                .where(LISTING_UNIT.ID.eq(unitId))
                .and(LISTING_UNIT.STATUS.in(SELLER_MOVABLE))
                .execute() > 0;
    }

    // ---------- checkout and after ----------

    /**
     * Picks cards to hold for an order: oldest acquired first, from a listing that
     * is on sale by a seller who is open for business.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} is the whole trick. A card another
     * checkout is already holding is stepped over instead of waited for, so when
     * twenty buyers race for the last card one of them gets it and the other
     * nineteen see zero rows straight away. Only this table is locked here; the
     * listing row is taken later, by the recount trigger.
     */
    public List<ListingUnit> lockForReservation(long listingId, int quantity) {
        return dsl.select(LISTING_UNIT.fields())
                .from(LISTING_UNIT)
                .join(LISTING).on(LISTING.ID.eq(LISTING_UNIT.LISTING_ID))
                .join(SELLER_PROFILE).on(SELLER_PROFILE.ID.eq(LISTING.SELLER_PROFILE_ID))
                .join(USER_ACCOUNT).on(USER_ACCOUNT.ID.eq(SELLER_PROFILE.USER_ID))
                .where(LISTING_UNIT.LISTING_ID.eq(listingId))
                .and(LISTING_UNIT.STATUS.eq(ListingUnitStatus.LISTED.name()))
                .and(LISTING_UNIT.DELETED_AT.isNull())
                .and(LISTING.STATUS.eq(ListingStatus.ACTIVE.name()))
                .and(LISTING.DELETED_AT.isNull())
                .and(SELLER_PROFILE.STATUS.eq(SellerStatus.VERIFIED.name()))
                .and(SELLER_PROFILE.VACATION_MODE.isFalse())
                .and(USER_ACCOUNT.STATUS.eq(UserStatus.ACTIVE))
                .and(USER_ACCOUNT.DELETED_AT.isNull())
                .orderBy(LISTING_UNIT.ACQUIRED_AT.asc().nullsLast(), LISTING_UNIT.ID.asc())
                .limit(quantity)
                .forUpdate().of(LISTING_UNIT).skipLocked()
                .fetch(r -> toUnit(r.into(LISTING_UNIT)));
    }

    /** Locked in id order, so two callers naming overlapping cards cannot deadlock. */
    public List<ListingUnit> lockByIds(Collection<Long> unitIds) {
        if (unitIds.isEmpty()) {
            return List.of();
        }
        return dsl.selectFrom(LISTING_UNIT)
                .where(LISTING_UNIT.ID.in(unitIds))
                .and(LISTING_UNIT.DELETED_AT.isNull())
                .orderBy(LISTING_UNIT.ID)
                .forUpdate()
                .fetch(ListingUnitRepository::toUnit);
    }

    /** @return how many actually moved; a card no longer in {@code from} is left alone */
    public int changeStatus(Collection<Long> unitIds, ListingUnitStatus from, ListingUnitStatus to) {
        if (unitIds.isEmpty()) {
            return 0;
        }
        return dsl.update(LISTING_UNIT)
                .set(LISTING_UNIT.STATUS, to.name())
                .where(LISTING_UNIT.ID.in(unitIds))
                .and(LISTING_UNIT.STATUS.eq(from.name()))
                .execute();
    }

    /**
     * Held cards go back on sale where their listing is still there to take them;
     * the rest go back into the seller's hands. A BLOCKED listing keeps its cards,
     * since a block is lifted more often than not.
     */
    public void release(Collection<Long> unitIds) {
        if (unitIds.isEmpty()) {
            return;
        }
        Condition held = LISTING_UNIT.ID.in(unitIds)
                .and(LISTING_UNIT.STATUS.eq(ListingUnitStatus.RESERVED.name()));

        dsl.update(LISTING_UNIT)
                .set(LISTING_UNIT.STATUS, ListingUnitStatus.LISTED.name())
                .where(held)
                .andExists(DSL.selectOne().from(LISTING)
                        .where(LISTING.ID.eq(LISTING_UNIT.LISTING_ID))
                        .and(LISTING.DELETED_AT.isNull())
                        .and(LISTING.STATUS.ne(ListingStatus.DELISTED.name())))
                .execute();

        dsl.update(LISTING_UNIT)
                .set(LISTING_UNIT.STATUS, ListingUnitStatus.IN_STOCK.name())
                .set(LISTING_UNIT.LISTING_ID, (Long) null)
                .where(held)
                .execute();
    }

    public int markSold(Collection<Long> unitIds, OffsetDateTime at) {
        if (unitIds.isEmpty()) {
            return 0;
        }
        return dsl.update(LISTING_UNIT)
                .set(LISTING_UNIT.STATUS, ListingUnitStatus.SOLD.name())
                .set(LISTING_UNIT.SOLD_AT, at)
                .where(LISTING_UNIT.ID.in(unitIds))
                .and(LISTING_UNIT.STATUS.eq(ListingUnitStatus.RESERVED.name()))
                .execute();
    }

    /** Back with the seller and on no listing, until they inspect it. */
    public boolean markReturned(long unitId) {
        return dsl.update(LISTING_UNIT)
                .set(LISTING_UNIT.STATUS, ListingUnitStatus.RETURNED.name())
                .set(LISTING_UNIT.LISTING_ID, (Long) null)
                .where(LISTING_UNIT.ID.eq(unitId))
                .and(LISTING_UNIT.STATUS.eq(ListingUnitStatus.SOLD.name()))
                .execute() > 0;
    }

    // ---------- helpers ----------

    private static Condition conditions(UnitQuery query) {
        Condition condition = LISTING_UNIT.SELLER_PROFILE_ID.eq(query.sellerProfileId())
                .and(LISTING_UNIT.DELETED_AT.isNull());

        if (query.listingId() != null) {
            condition = condition.and(LISTING_UNIT.LISTING_ID.eq(query.listingId()));
        }
        if (query.status() != null) {
            condition = condition.and(LISTING_UNIT.STATUS.eq(query.status().name()));
        }
        if (query.variantId() != null) {
            condition = condition.and(LISTING_UNIT.CATALOG_VARIANT_ID.eq(query.variantId()));
        }
        if (query.condition() != null) {
            condition = condition.and(LISTING_UNIT.CONDITION_CODE.eq(query.condition().name()));
        }
        return condition;
    }

    static ListingUnit toUnit(ListingUnitRecord r) {
        return new ListingUnit(
                r.getId(),
                r.getPublicUid(),
                r.getSellerProfileId(),
                r.getCatalogVariantId(),
                CardCondition.valueOf(r.getConditionCode()),
                r.getListingId(),
                ListingUnitStatus.valueOf(r.getStatus()),
                r.getCertNumber(),
                r.getAcquisitionCost(),
                r.getAcquiredAt(),
                r.getUnitNote(),
                r.getSoldAt(),
                r.getCreatedAt(),
                r.getUpdatedAt());
    }

    // ---------- shapes ----------

    public record UnitQuery(
            long sellerProfileId,
            Long listingId,
            ListingUnitStatus status,
            Long variantId,
            CardCondition condition,
            int limit,
            int offset) {
    }

    /**
     * @param listingId null to keep the cards in hand
     * @param unitCost  what each card cost; required, because profit cannot be
     *                  worked out later for stock that came in without one
     */
    public record NewUnits(
            long sellerProfileId,
            long catalogVariantId,
            CardCondition condition,
            Long listingId,
            int quantity,
            BigDecimal unitCost,
            OffsetDateTime acquiredAt,
            String certNumber,
            String unitNote) {
    }
}
