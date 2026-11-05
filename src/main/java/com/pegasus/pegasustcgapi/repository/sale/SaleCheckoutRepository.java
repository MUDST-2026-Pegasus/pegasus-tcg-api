package com.pegasus.pegasustcgapi.repository.sale;

import static com.pegasus.pegasustcgapi.jooq.tables.ListingSalePrice.LISTING_SALE_PRICE;
import static com.pegasus.pegasustcgapi.jooq.tables.OrderItem.ORDER_ITEM;
import static com.pegasus.pegasustcgapi.jooq.tables.PriceSale.PRICE_SALE;
import static com.pegasus.pegasustcgapi.jooq.tables.PriceSaleListing.PRICE_SALE_LISTING;
import static com.pegasus.pegasustcgapi.jooq.tables.SalesOrder.SALES_ORDER;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerOrder.SELLER_ORDER;

import com.pegasus.pegasustcgapi.model.order.SellerOrderStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/**
 * What checkout holds against a sale, and gives back [TCG-680]: flash quota in
 * {@code price_sale_listing.sold_count}, Pegasus budget in
 * {@code price_sale.subsidy_reserved}, and what each order line holds of them.
 * Every take is one guarded UPDATE, so the limit holds however many checkouts race.
 */
@Repository
public class SaleCheckoutRepository {

    private final DSLContext dsl;

    public SaleCheckoutRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * Counts {@code qty} more cards of a listing as sold at the sale price, if the
     * quota has room for them.
     *
     * @param saleQuota the sale's per-listing quota, used when the listing's row has none
     * @return false when it does not, and nothing changed
     */
    public boolean takeQuota(long saleId, long listingId, int qty, Integer saleQuota) {
        dsl.insertInto(PRICE_SALE_LISTING)
                .set(PRICE_SALE_LISTING.SALE_ID, saleId)
                .set(PRICE_SALE_LISTING.LISTING_ID, listingId)
                .onConflictDoNothing()
                .execute();
        return dsl.update(PRICE_SALE_LISTING)
                .set(PRICE_SALE_LISTING.SOLD_COUNT, PRICE_SALE_LISTING.SOLD_COUNT.plus(qty))
                .where(PRICE_SALE_LISTING.SALE_ID.eq(saleId))
                .and(PRICE_SALE_LISTING.LISTING_ID.eq(listingId))
                .and(PRICE_SALE_LISTING.SOLD_COUNT.plus(qty).le(
                        DSL.coalesce(PRICE_SALE_LISTING.QUOTA, DSL.val(saleQuota, PRICE_SALE_LISTING.QUOTA))))
                .execute() > 0;
    }

    public void giveBackQuota(long saleId, long listingId, int qty) {
        dsl.update(PRICE_SALE_LISTING)
                .set(PRICE_SALE_LISTING.SOLD_COUNT, DSL.greatest(PRICE_SALE_LISTING.SOLD_COUNT.minus(qty), DSL.inline(0)))
                .where(PRICE_SALE_LISTING.SALE_ID.eq(saleId))
                .and(PRICE_SALE_LISTING.LISTING_ID.eq(listingId))
                .execute();
    }

    /**
     * Holds {@code amount} of a campaign's budget, if it fits and the campaign has
     * not already run short.
     *
     * @return false when it does not, and nothing changed
     */
    public boolean takeBudget(long saleId, BigDecimal amount) {
        return dsl.update(PRICE_SALE)
                .set(PRICE_SALE.SUBSIDY_RESERVED, PRICE_SALE.SUBSIDY_RESERVED.plus(amount))
                .where(PRICE_SALE.ID.eq(saleId))
                .and(PRICE_SALE.BUDGET_EXHAUSTED_AT.isNull())
                .and(PRICE_SALE.BUDGET_CAP.isNull()
                        .or(PRICE_SALE.SUBSIDY_RESERVED.plus(amount).le(PRICE_SALE.BUDGET_CAP)))
                .execute() > 0;
    }

    public void giveBackBudget(long saleId, BigDecimal amount) {
        dsl.update(PRICE_SALE)
                .set(PRICE_SALE.SUBSIDY_RESERVED,
                        DSL.greatest(PRICE_SALE.SUBSIDY_RESERVED.minus(amount), DSL.inline(BigDecimal.ZERO)))
                .where(PRICE_SALE.ID.eq(saleId))
                .execute();
    }

    /** Ends a campaign as BUDGET_EXHAUSTED. @return false when it already was */
    public boolean markBudgetExhausted(long saleId, OffsetDateTime at) {
        return dsl.update(PRICE_SALE)
                .set(PRICE_SALE.BUDGET_EXHAUSTED_AT, at)
                .where(PRICE_SALE.ID.eq(saleId))
                .and(PRICE_SALE.BUDGET_CAP.isNotNull())
                .and(PRICE_SALE.BUDGET_EXHAUSTED_AT.isNull())
                .execute() > 0;
    }

    /** Listings selling at this sale's price now, by the last refresh. */
    public List<Long> listingsPricedBy(long saleId) {
        return dsl.select(LISTING_SALE_PRICE.LISTING_ID)
                .from(LISTING_SALE_PRICE)
                .where(LISTING_SALE_PRICE.SALE_ID.eq(saleId))
                .fetch(LISTING_SALE_PRICE.LISTING_ID);
    }

    /**
     * Holds a buyer's place against one sale until the transaction ends, so two
     * checkouts of the same buyer cannot both pass the per-buyer limit.
     */
    public void lockBuyer(long saleId, long buyerId) {
        dsl.select(DSL.field("pg_advisory_xact_lock(hashtextextended({0}, 0))", Object.class,
                        DSL.val("price-sale-buyer:" + saleId + ":" + buyerId)))
                .fetch();
    }

    /** Cards this buyer has bought at this sale's price, in orders not cancelled. */
    public int boughtAtSale(long saleId, long buyerId) {
        return dsl.select(DSL.coalesce(DSL.sum(ORDER_ITEM.QUANTITY), BigDecimal.ZERO))
                .from(ORDER_ITEM)
                .join(SELLER_ORDER).on(SELLER_ORDER.ID.eq(ORDER_ITEM.SELLER_ORDER_ID))
                .join(SALES_ORDER).on(SALES_ORDER.ID.eq(SELLER_ORDER.SALES_ORDER_ID))
                .where(ORDER_ITEM.SALE_ID.eq(saleId))
                .and(SALES_ORDER.BUYER_ID.eq(buyerId))
                .and(SELLER_ORDER.STATUS.ne(SellerOrderStatus.CANCELLED.name()))
                .fetchSingle(0, int.class);
    }

    /** A line of an order that still holds quota or budget. */
    public record HeldLine(long orderItemId, long saleId, Long listingId, int quotaHeld, BigDecimal subsidyHeld) {
    }

    /** The lines of a seller order still holding something, locked, and then holding nothing. */
    public List<HeldLine> takeHeld(long sellerOrderId) {
        List<HeldLine> held = dsl.select(ORDER_ITEM.ID, ORDER_ITEM.SALE_ID, ORDER_ITEM.LISTING_ID,
                        ORDER_ITEM.SALE_QUOTA_HELD, ORDER_ITEM.SALE_SUBSIDY_HELD)
                .from(ORDER_ITEM)
                .where(ORDER_ITEM.SELLER_ORDER_ID.eq(sellerOrderId))
                .and(ORDER_ITEM.SALE_ID.isNotNull())
                .and(ORDER_ITEM.SALE_QUOTA_HELD.gt(0).or(ORDER_ITEM.SALE_SUBSIDY_HELD.gt(BigDecimal.ZERO)))
                .orderBy(ORDER_ITEM.ID)
                .forUpdate()
                .fetch(r -> new HeldLine(r.value1(), r.value2(), r.value3(), r.value4(), r.value5()));
        if (!held.isEmpty()) {
            dsl.update(ORDER_ITEM)
                    .set(ORDER_ITEM.SALE_QUOTA_HELD, 0)
                    .set(ORDER_ITEM.SALE_SUBSIDY_HELD, BigDecimal.ZERO)
                    .where(ORDER_ITEM.ID.in(held.stream().map(HeldLine::orderItemId).toList()))
                    .execute();
        }
        return held;
    }
}
