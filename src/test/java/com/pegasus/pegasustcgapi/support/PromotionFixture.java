package com.pegasus.pegasustcgapi.support;

import com.pegasus.pegasustcgapi.model.Promotion;
import com.pegasus.pegasustcgapi.model.Promotion.DiscountType;
import com.pegasus.pegasustcgapi.model.Promotion.OwnerType;
import com.pegasus.pegasustcgapi.model.Promotion.ScopeType;
import com.pegasus.pegasustcgapi.model.Promotion.Visibility;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Builds {@link Promotion} values for unit tests; defaults to an active, unlimited code. */
public final class PromotionFixture {

    public static final OffsetDateTime NOW = OffsetDateTime.of(2026, 10, 30, 12, 0, 0, 0, ZoneOffset.ofHours(7));

    private static final AtomicLong IDS = new AtomicLong(1);

    private final String code;
    private OwnerType ownerType = OwnerType.PLATFORM;
    private Long sellerProfileId;
    private DiscountType discountType = DiscountType.FIXED;
    private BigDecimal discountValue = BigDecimal.ZERO;
    private BigDecimal maxDiscount;
    private BigDecimal minSpend = BigDecimal.ZERO;
    private ScopeType scopeType = ScopeType.ALL;
    private List<Long> scopeIds = List.of();
    private Integer usageLimitTotal;
    private int usedCount;
    private OffsetDateTime startsAt = NOW.minusDays(1);
    private OffsetDateTime endsAt = NOW.plusDays(30);
    private OffsetDateTime pausedAt;

    private PromotionFixture(String code) {
        this.code = code;
    }

    public static PromotionFixture platform(String code) {
        return new PromotionFixture(code);
    }

    public static PromotionFixture seller(String code, long sellerProfileId) {
        PromotionFixture f = new PromotionFixture(code);
        f.ownerType = OwnerType.SELLER;
        f.sellerProfileId = sellerProfileId;
        return f;
    }

    public PromotionFixture percent(String value, String max) {
        discountType = DiscountType.PERCENT;
        discountValue = new BigDecimal(value);
        maxDiscount = max == null ? null : new BigDecimal(max);
        return this;
    }

    public PromotionFixture fixed(String value) {
        discountType = DiscountType.FIXED;
        discountValue = new BigDecimal(value);
        return this;
    }

    public PromotionFixture freeShipping(String maxPerShop) {
        discountType = DiscountType.FREE_SHIPPING;
        discountValue = BigDecimal.ZERO;
        maxDiscount = maxPerShop == null ? null : new BigDecimal(maxPerShop);
        return this;
    }

    public PromotionFixture minSpend(String value) {
        minSpend = new BigDecimal(value);
        return this;
    }

    public PromotionFixture games(Long... ids) {
        scopeType = ScopeType.GAME;
        scopeIds = List.of(ids);
        return this;
    }

    public PromotionFixture categories(Long... ids) {
        scopeType = ScopeType.CATEGORY;
        scopeIds = List.of(ids);
        return this;
    }

    public PromotionFixture usage(Integer limit, int used) {
        usageLimitTotal = limit;
        usedCount = used;
        return this;
    }

    public PromotionFixture window(OffsetDateTime starts, OffsetDateTime ends) {
        startsAt = starts;
        endsAt = ends;
        return this;
    }

    public PromotionFixture pausedAt(OffsetDateTime at) {
        pausedAt = at;
        return this;
    }

    public Promotion build() {
        return new Promotion(IDS.getAndIncrement(), code, code, null, ownerType, sellerProfileId,
                discountType, discountValue, maxDiscount, minSpend, scopeType, scopeIds,
                Visibility.PUBLIC, false, usageLimitTotal, 1, usedCount,
                false, false, null, 0, false, null,
                startsAt, endsAt, pausedAt, null, null, null, 0, startsAt, startsAt);
    }
}
