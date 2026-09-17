package com.pegasus.pegasustcgapi.repository;

import static com.pegasus.pegasustcgapi.jooq.tables.ListingPriceHistory.LISTING_PRICE_HISTORY;

import com.pegasus.pegasustcgapi.jooq.tables.records.ListingPriceHistoryRecord;
import com.pegasus.pegasustcgapi.model.ListingPriceChange;
import com.pegasus.pegasustcgapi.model.PricingMode;
import java.math.BigDecimal;
import java.util.List;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** Reads and writes {@code listing_price_history}. Append-only: nothing here updates or deletes. */
@Repository
public class ListingPriceHistoryRepository {

    private final DSLContext dsl;

    public ListingPriceHistoryRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * @param oldPrice  null for the row written when the listing is created
     * @param changedBy the person; null when the nightly job made the change
     */
    public void insert(long listingId, BigDecimal oldPrice, BigDecimal newPrice, PricingMode mode,
            Long changedBy, String reason) {

        dsl.insertInto(LISTING_PRICE_HISTORY)
                .set(LISTING_PRICE_HISTORY.LISTING_ID, listingId)
                .set(LISTING_PRICE_HISTORY.OLD_PRICE, oldPrice)
                .set(LISTING_PRICE_HISTORY.NEW_PRICE, newPrice)
                .set(LISTING_PRICE_HISTORY.PRICING_MODE, mode.name())
                .set(LISTING_PRICE_HISTORY.CHANGED_BY, changedBy)
                .set(LISTING_PRICE_HISTORY.REASON, reason)
                .execute();
    }

    /** Newest first. */
    public List<ListingPriceChange> findPage(long listingId, int limit, int offset) {
        return dsl.selectFrom(LISTING_PRICE_HISTORY)
                .where(LISTING_PRICE_HISTORY.LISTING_ID.eq(listingId))
                .orderBy(LISTING_PRICE_HISTORY.CREATED_AT.desc(), LISTING_PRICE_HISTORY.ID.desc())
                .limit(limit)
                .offset(offset)
                .fetch(ListingPriceHistoryRepository::toChange);
    }

    public long count(long listingId) {
        return dsl.fetchCount(LISTING_PRICE_HISTORY, LISTING_PRICE_HISTORY.LISTING_ID.eq(listingId));
    }

    private static ListingPriceChange toChange(ListingPriceHistoryRecord r) {
        return new ListingPriceChange(
                r.getId(),
                r.getListingId(),
                r.getOldPrice(),
                r.getNewPrice(),
                PricingMode.valueOf(r.getPricingMode()),
                r.getChangedBy(),
                r.getReason(),
                r.getCreatedAt());
    }
}
