package com.pegasus.pegasustcgapi.repository.order;

import static com.pegasus.pegasustcgapi.jooq.tables.Auction.AUCTION;
import static com.pegasus.pegasustcgapi.jooq.tables.OrderItem.ORDER_ITEM;
import static com.pegasus.pegasustcgapi.jooq.tables.OrderItemUnit.ORDER_ITEM_UNIT;
import static com.pegasus.pegasustcgapi.jooq.tables.SalesOrder.SALES_ORDER;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerOrder.SELLER_ORDER;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerShippingOption.SELLER_SHIPPING_OPTION;

import com.pegasus.pegasustcgapi.jooq.tables.records.OrderItemRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.OrderItemUnitRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.SalesOrderRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.SellerOrderRecord;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/**
 * Creates sales orders, seller orders and order items, and loads one order by id, by owner,
 * by idempotency key or under a row lock.
 *
 * <p>Status changes, shipments and history live in {@link OrderTransitionRepository}; lists and
 * batch reads in {@link OrderQueryRepository}; card and shop lookups in
 * {@link OrderReferenceQueryRepository}.
 */
@Repository
public class OrderRepository {

    private final DSLContext dsl;
    private final Clock clock;

    @Autowired
    public OrderRepository(DSLContext dsl, Clock clock) {
        this.dsl = dsl;
        this.clock = clock;
    }

    public OrderRepository(DSLContext dsl) {
        this(dsl, Clock.systemUTC());
    }

    /**
     * The next {@code PGS-YYYYMMDD-NNNNNN}.
     *
     * <p>The counter comes from {@code order_number_seq} rather than from a count
     * of today's rows: two checkouts running at once would read the same count and
     * collide on {@code uq_sales_order_number}. A sequence is also outside
     * transaction control, so a checkout that rolls back burns its number instead
     * of handing it to the next buyer.
     */
    public String nextOrderNumber() {
        long counter = dsl.nextval(DSL.sequence(DSL.name("order_number_seq"), Long.class));
        return "PGS-%s-%06d".formatted(
                DateTimeFormatter.BASIC_ISO_DATE.format(OffsetDateTime.now(clock)), counter);
    }

    public Optional<SalesOrderRecord> findSalesOrderById(long id) {
        return dsl.selectFrom(SALES_ORDER)
                .where(SALES_ORDER.ID.eq(id))
                .fetchOptional();
    }

    public Optional<SalesOrderRecord> findSalesOrderByIdempotencyKey(String idempotencyKey) {
        return dsl.selectFrom(SALES_ORDER)
                .where(SALES_ORDER.IDEMPOTENCY_KEY.eq(idempotencyKey))
                .fetchOptional();
    }

    public SalesOrderRecord insertSalesOrder(
            String orderNumber,
            long buyerId,
            String currency,
            BigDecimal itemsSubtotal,
            BigDecimal shippingTotal,
            BigDecimal discountTotal,
            BigDecimal grandTotal,
            Long shippingAddressId,
            JSONB shippingAddressSnapshot,
            String buyerNote,
            String idempotencyKey) {

        return dsl.insertInto(SALES_ORDER)
                .set(SALES_ORDER.ORDER_NUMBER, orderNumber)
                .set(SALES_ORDER.BUYER_ID, buyerId)
                .set(SALES_ORDER.STATUS, "PENDING_PAYMENT")
                .set(SALES_ORDER.CURRENCY, currency)
                .set(SALES_ORDER.ITEMS_SUBTOTAL, itemsSubtotal)
                .set(SALES_ORDER.SHIPPING_TOTAL, shippingTotal)
                .set(SALES_ORDER.DISCOUNT_TOTAL, discountTotal)
                .set(SALES_ORDER.GRAND_TOTAL, grandTotal)
                .set(SALES_ORDER.SHIPPING_ADDRESS_ID, shippingAddressId)
                .set(SALES_ORDER.SHIPPING_ADDRESS_SNAPSHOT, shippingAddressSnapshot)
                .set(SALES_ORDER.BUYER_NOTE, buyerNote)
                .set(SALES_ORDER.IDEMPOTENCY_KEY, idempotencyKey)
                .returning()
                .fetchSingle();
    }

    /** Set the source before committing the auction checkout. */
    public void markAuctionSource(long orderId, long auctionId) {
        dsl.update(SALES_ORDER).set(SALES_ORDER.SOURCE, "AUCTION")
                .set(SALES_ORDER.AUCTION_ID, auctionId)
                .where(SALES_ORDER.ID.eq(orderId)).execute();
    }

    /**
     * @param commissionRatePercent the rate the quote was made at; a snapshot, so
     *                              changing the rule later never rewrites this order
     * @param shippingOptionId      which of the seller's delivery choices was used,
     *                              null when they had none configured
     */
    public SellerOrderRecord insertSellerOrder(
            long salesOrderId,
            long sellerProfileId,
            String sellerOrderNumber,
            BigDecimal itemsSubtotal,
            BigDecimal shippingFee,
            BigDecimal discountAmount,
            BigDecimal grandTotal,
            BigDecimal commissionRatePercent,
            BigDecimal commissionAmount,
            BigDecimal sellerNetAmount,
            Long shippingOptionId) {
        return insertSellerOrder(salesOrderId, sellerProfileId, sellerOrderNumber, itemsSubtotal, shippingFee,
                discountAmount, BigDecimal.ZERO, grandTotal, commissionRatePercent, commissionAmount,
                sellerNetAmount, shippingOptionId);
    }

    /**
     * An order part with discount codes on it [TCG-569]. The shop pays
     * {@code sellerDiscount} itself; Pegasus pays {@code platformDiscount} back at release.
     */
    public SellerOrderRecord insertSellerOrder(
            long salesOrderId,
            long sellerProfileId,
            String sellerOrderNumber,
            BigDecimal itemsSubtotal,
            BigDecimal shippingFee,
            BigDecimal sellerDiscount,
            BigDecimal platformDiscount,
            BigDecimal grandTotal,
            BigDecimal commissionRatePercent,
            BigDecimal commissionAmount,
            BigDecimal sellerNetAmount,
            Long shippingOptionId) {
        return insertSellerOrder(salesOrderId, sellerProfileId, sellerOrderNumber, itemsSubtotal, shippingFee,
                sellerDiscount, platformDiscount, BigDecimal.ZERO, grandTotal, commissionRatePercent,
                commissionAmount, sellerNetAmount, shippingOptionId);
    }

    /**
     * An order part with codes and sale prices on it [TCG-680].
     *
     * @param saleSubsidy what Pegasus pays the shop at release for its campaigns'
     *                    discounts on these cards ({@code SALE_SUBSIDY})
     */
    public SellerOrderRecord insertSellerOrder(
            long salesOrderId,
            long sellerProfileId,
            String sellerOrderNumber,
            BigDecimal itemsSubtotal,
            BigDecimal shippingFee,
            BigDecimal sellerDiscount,
            BigDecimal platformDiscount,
            BigDecimal saleSubsidy,
            BigDecimal grandTotal,
            BigDecimal commissionRatePercent,
            BigDecimal commissionAmount,
            BigDecimal sellerNetAmount,
            Long shippingOptionId) {

        BigDecimal discountAmount = sellerDiscount.add(platformDiscount);
        // Snapshot the chosen option as it reads right now, in the same transaction
        // that resolved it [TCG-503]; later edits to the option never reach this row.
        var option = shippingOptionId == null ? null : dsl.selectFrom(SELLER_SHIPPING_OPTION)
                .where(SELLER_SHIPPING_OPTION.ID.eq(shippingOptionId))
                .fetchOne();
        return dsl.insertInto(SELLER_ORDER)
                .set(SELLER_ORDER.SALES_ORDER_ID, salesOrderId)
                .set(SELLER_ORDER.SELLER_PROFILE_ID, sellerProfileId)
                .set(SELLER_ORDER.SELLER_ORDER_NUMBER, sellerOrderNumber)
                .set(SELLER_ORDER.STATUS, "PENDING_PAYMENT")
                .set(SELLER_ORDER.ITEMS_SUBTOTAL, itemsSubtotal)
                .set(SELLER_ORDER.SHIPPING_FEE, shippingFee)
                .set(SELLER_ORDER.DISCOUNT_AMOUNT, discountAmount)
                .set(SELLER_ORDER.SELLER_DISCOUNT_AMOUNT, sellerDiscount)
                .set(SELLER_ORDER.PLATFORM_DISCOUNT_AMOUNT, platformDiscount)
                .set(SELLER_ORDER.SALE_SUBSIDY_AMOUNT, saleSubsidy)
                .set(SELLER_ORDER.GRAND_TOTAL, grandTotal)
                .set(SELLER_ORDER.COMMISSION_RATE_PERCENT,
                        commissionRatePercent != null ? commissionRatePercent : BigDecimal.ZERO)
                .set(SELLER_ORDER.COMMISSION_AMOUNT, commissionAmount)
                .set(SELLER_ORDER.SELLER_NET_AMOUNT, sellerNetAmount)
                .set(SELLER_ORDER.SHIPPING_OPTION_ID, shippingOptionId)
                .set(SELLER_ORDER.SHIPPING_OPTION_NAME, option == null ? null : option.getName())
                .set(SELLER_ORDER.SHIPPING_CARRIER_CODE, option == null ? null : option.getCarrierCode())
                .set(SELLER_ORDER.SHIPPING_EST_DAYS_MIN, option == null ? null : option.getEstDaysMin())
                .set(SELLER_ORDER.SHIPPING_EST_DAYS_MAX, option == null ? null : option.getEstDaysMax())
                .returning()
                .fetchSingle();
    }

    /**
     * @param unitCostSnapshot the seller's weighted average cost at the moment of sale;
     *                         profit on this order is computed from it and never recomputed
     */
    public OrderItemRecord insertOrderItem(
            long sellerOrderId,
            Long listingId,
            long catalogVariantId,
            int quantity,
            BigDecimal unitPrice,
            BigDecimal lineTotal,
            BigDecimal unitCostSnapshot,
            String productNameSnapshot,
            String variantLabelSnapshot,
            String conditionSnapshot,
            String gameNameSnapshot,
            String imageKeySnapshot) {
        return insertOrderItem(sellerOrderId, listingId, catalogVariantId, quantity, unitPrice, lineTotal,
                BigDecimal.ZERO, BigDecimal.ZERO, unitCostSnapshot, productNameSnapshot, variantLabelSnapshot,
                conditionSnapshot, gameNameSnapshot, imageKeySnapshot);
    }

    /**
     * @param discountAllocated       this line's share of the order part's item discounts,
     *                                so a partial return refunds what the buyer paid for it
     * @param sellerDiscountAllocated the shop-code part of it; the rest is Pegasus's
     */
    public OrderItemRecord insertOrderItem(
            long sellerOrderId,
            Long listingId,
            long catalogVariantId,
            int quantity,
            BigDecimal unitPrice,
            BigDecimal lineTotal,
            BigDecimal discountAllocated,
            BigDecimal sellerDiscountAllocated,
            BigDecimal unitCostSnapshot,
            String productNameSnapshot,
            String variantLabelSnapshot,
            String conditionSnapshot,
            String gameNameSnapshot,
            String imageKeySnapshot) {
        return insertOrderItem(sellerOrderId, listingId, catalogVariantId, quantity, unitPrice, lineTotal,
                discountAllocated, sellerDiscountAllocated, unitCostSnapshot, productNameSnapshot,
                variantLabelSnapshot, conditionSnapshot, gameNameSnapshot, imageKeySnapshot, null);
    }

    /**
     * How a line sold against its asking price [TCG-680].
     *
     * @param listPrice    the asking price per card; {@code unit_price = listPrice - saleDiscount}
     * @param saleDiscount per card; zero off sale
     * @param saleId       null off sale
     * @param fundedBy     PLATFORM or SELLER; null off sale
     * @param quotaHeld    cards counted against the flash quota until paid
     * @param subsidyHeld  baht counted against the campaign budget until paid
     */
    public record SaleSnapshot(BigDecimal listPrice, BigDecimal saleDiscount, Long saleId, String fundedBy,
                               int quotaHeld, BigDecimal subsidyHeld) {
    }

    /** @param sale how the line sold against its asking price; null when it sold at it */
    public OrderItemRecord insertOrderItem(
            long sellerOrderId,
            Long listingId,
            long catalogVariantId,
            int quantity,
            BigDecimal unitPrice,
            BigDecimal lineTotal,
            BigDecimal discountAllocated,
            BigDecimal sellerDiscountAllocated,
            BigDecimal unitCostSnapshot,
            String productNameSnapshot,
            String variantLabelSnapshot,
            String conditionSnapshot,
            String gameNameSnapshot,
            String imageKeySnapshot,
            SaleSnapshot sale) {

        SaleSnapshot s = sale != null ? sale : new SaleSnapshot(unitPrice, BigDecimal.ZERO, null, null, 0, BigDecimal.ZERO);
        return dsl.insertInto(ORDER_ITEM)
                .set(ORDER_ITEM.LIST_PRICE, s.listPrice())
                .set(ORDER_ITEM.SALE_DISCOUNT, s.saleDiscount())
                .set(ORDER_ITEM.SALE_ID, s.saleId())
                .set(ORDER_ITEM.SALE_FUNDED_BY, s.fundedBy())
                .set(ORDER_ITEM.SALE_QUOTA_HELD, s.quotaHeld())
                .set(ORDER_ITEM.SALE_SUBSIDY_HELD, s.subsidyHeld())
                .set(ORDER_ITEM.SELLER_ORDER_ID, sellerOrderId)
                .set(ORDER_ITEM.LISTING_ID, listingId)
                .set(ORDER_ITEM.CATALOG_VARIANT_ID, catalogVariantId)
                .set(ORDER_ITEM.QUANTITY, quantity)
                .set(ORDER_ITEM.UNIT_PRICE, unitPrice)
                .set(ORDER_ITEM.LINE_TOTAL, lineTotal)
                .set(ORDER_ITEM.DISCOUNT_ALLOCATED, discountAllocated)
                .set(ORDER_ITEM.SELLER_DISCOUNT_ALLOCATED, sellerDiscountAllocated)
                .set(ORDER_ITEM.UNIT_COST_SNAPSHOT, unitCostSnapshot != null ? unitCostSnapshot : BigDecimal.ZERO)
                .set(ORDER_ITEM.PRODUCT_NAME_SNAPSHOT, productNameSnapshot)
                .set(ORDER_ITEM.VARIANT_LABEL_SNAPSHOT, variantLabelSnapshot)
                .set(ORDER_ITEM.CONDITION_SNAPSHOT, conditionSnapshot)
                .set(ORDER_ITEM.GAME_NAME_SNAPSHOT, gameNameSnapshot)
                .set(ORDER_ITEM.IMAGE_KEY_SNAPSHOT, imageKeySnapshot)
                .returning()
                .fetchSingle();
    }

    /**
     * One statement for the whole order item. Checkout holds the {@code FOR UPDATE
     * SKIP LOCKED} row locks taken by the reservation until it commits, so a
     * round trip per card is contention every concurrent checkout pays for.
     */
    public void insertOrderItemUnits(long orderItemId, Collection<Long> unitIds) {
        if (unitIds.isEmpty()) {
            return;
        }
        List<OrderItemUnitRecord> records = new ArrayList<>(unitIds.size());
        for (Long unitId : unitIds) {
            OrderItemUnitRecord record = dsl.newRecord(ORDER_ITEM_UNIT);
            record.setOrderItemId(orderItemId);
            record.setListingUnitId(unitId);
            records.add(record);
        }
        dsl.batchInsert(records).execute();
    }

    public Optional<SalesOrderRecord> findSalesOrderByIdAndBuyerId(long id, long buyerId) {
        return dsl.selectFrom(SALES_ORDER)
                .where(SALES_ORDER.ID.eq(id))
                .and(SALES_ORDER.BUYER_ID.eq(buyerId))
                .fetchOptional();
    }

    public Optional<SalesOrderRecord> findSalesOrderByIdForUpdate(long id) {
        return dsl.selectFrom(SALES_ORDER)
                .where(SALES_ORDER.ID.eq(id))
                .forUpdate()
                .fetchOptional();
    }

    public Optional<SellerOrderRecord> findSellerOrderById(long id) {
        return dsl.selectFrom(SELLER_ORDER)
                .where(SELLER_ORDER.ID.eq(id))
                .fetchOptional();
    }

    public Optional<SellerOrderRecord> findSellerOrderByIdAndSellerProfileId(long id, long sellerProfileId) {
        return dsl.selectFrom(SELLER_ORDER)
                .where(SELLER_ORDER.ID.eq(id))
                .and(SELLER_ORDER.SELLER_PROFILE_ID.eq(sellerProfileId))
                .fetchOptional();
    }
}
