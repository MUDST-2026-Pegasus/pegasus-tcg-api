package com.pegasus.pegasustcgapi.support;

import com.pegasus.pegasustcgapi.model.catalog.CardCondition;
import com.pegasus.pegasustcgapi.model.sale.PriceSale;
import com.pegasus.pegasustcgapi.model.sale.PriceSale.DiscountType;
import com.pegasus.pegasustcgapi.model.sale.PriceSale.OwnerType;
import com.pegasus.pegasustcgapi.model.sale.PriceSale.SaleType;
import com.pegasus.pegasustcgapi.model.sale.PriceSale.ScopeType;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Builds {@link PriceSale} values for unit tests; defaults to a running 10% Pegasus sale over everything. */
public final class PriceSaleFixture {

    public static final OffsetDateTime NOW = OffsetDateTime.of(2026, 11, 4, 12, 0, 0, 0, ZoneOffset.ofHours(7));

    private static final AtomicLong IDS = new AtomicLong(1);

    private final long id = IDS.getAndIncrement();
    private OwnerType ownerType = OwnerType.PLATFORM;
    private Long sellerProfileId;
    private SaleType saleType = SaleType.CATEGORY_SALE;
    private DiscountType discountType = DiscountType.PERCENT;
    private BigDecimal discountValue = new BigDecimal("10");
    private BigDecimal maxDiscountPerUnit;
    private ScopeType scopeType = ScopeType.ALL;
    private List<Long> scopeIds = List.of();
    private List<CardCondition> conditions = List.of();
    private BigDecimal minUnitPrice;
    private Integer perListingQuota;
    private BigDecimal budgetCap;
    private BigDecimal subsidyReserved = BigDecimal.ZERO;
    private OffsetDateTime startsAt = NOW.minusHours(1);
    private OffsetDateTime endsAt = NOW.plusDays(1);
    private OffsetDateTime pausedAt;
    private OffsetDateTime endedAt;
    private List<Long> excludedSellerIds = List.of();

    private PriceSaleFixture() {
    }

    public static PriceSaleFixture platform() {
        return new PriceSaleFixture();
    }

    public static PriceSaleFixture flash(int perListingQuota) {
        PriceSaleFixture f = new PriceSaleFixture();
        f.saleType = SaleType.FLASH_SALE;
        f.perListingQuota = perListingQuota;
        return f;
    }

    public static PriceSaleFixture shop(long sellerProfileId) {
        PriceSaleFixture f = new PriceSaleFixture();
        f.ownerType = OwnerType.SELLER;
        f.sellerProfileId = sellerProfileId;
        f.saleType = SaleType.SHOP_SALE;
        f.scopeType = ScopeType.SHOP;
        return f;
    }

    public PriceSaleFixture percent(String value, String maxPerUnit) {
        discountType = DiscountType.PERCENT;
        discountValue = new BigDecimal(value);
        maxDiscountPerUnit = maxPerUnit == null ? null : new BigDecimal(maxPerUnit);
        return this;
    }

    public PriceSaleFixture fixedOff(String value) {
        discountType = DiscountType.FIXED_OFF;
        discountValue = new BigDecimal(value);
        maxDiscountPerUnit = null;
        return this;
    }

    /** A shop sale of chosen listings, each priced in its price_sale_listing row. */
    public PriceSaleFixture fixedPrice() {
        discountType = DiscountType.FIXED_PRICE;
        discountValue = BigDecimal.ZERO;
        maxDiscountPerUnit = null;
        scopeType = ScopeType.LISTINGS;
        scopeIds = List.of();
        return this;
    }

    public PriceSaleFixture scope(ScopeType type, Long... ids) {
        scopeType = type;
        scopeIds = List.of(ids);
        return this;
    }

    public PriceSaleFixture conditions(CardCondition... values) {
        conditions = List.of(values);
        return this;
    }

    public PriceSaleFixture minUnitPrice(String value) {
        minUnitPrice = new BigDecimal(value);
        return this;
    }

    public PriceSaleFixture budget(String cap, String reserved) {
        budgetCap = cap == null ? null : new BigDecimal(cap);
        subsidyReserved = new BigDecimal(reserved);
        return this;
    }

    public PriceSaleFixture window(OffsetDateTime starts, OffsetDateTime ends) {
        startsAt = starts;
        endsAt = ends;
        return this;
    }

    public PriceSaleFixture pausedAt(OffsetDateTime at) {
        pausedAt = at;
        return this;
    }

    /** Shops a Pegasus campaign leaves out. */
    public PriceSaleFixture excluding(Long... sellerProfileIds) {
        excludedSellerIds = List.of(sellerProfileIds);
        return this;
    }

    public PriceSaleFixture endedAt(OffsetDateTime at) {
        endedAt = at;
        return this;
    }

    public PriceSale build() {
        return new PriceSale(id, "Sale " + id, "sale-" + id, ownerType, sellerProfileId, saleType,
                discountType, discountValue, maxDiscountPerUnit, scopeType, scopeIds, conditions,
                minUnitPrice, perListingQuota, null, budgetCap, subsidyReserved, null, false, null,
                startsAt, endsAt, pausedAt, null, null, endedAt, null, 0, startsAt, startsAt, null,
                excludedSellerIds);
    }
}
