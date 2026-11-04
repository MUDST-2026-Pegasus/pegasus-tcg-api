package com.pegasus.pegasustcgapi.repository.sale;

import static com.pegasus.pegasustcgapi.jooq.tables.CardSet.CARD_SET;
import static com.pegasus.pegasustcgapi.jooq.tables.CatalogCategory.CATALOG_CATEGORY;
import static com.pegasus.pegasustcgapi.jooq.tables.CatalogProduct.CATALOG_PRODUCT;
import static com.pegasus.pegasustcgapi.jooq.tables.CatalogVariant.CATALOG_VARIANT;
import static com.pegasus.pegasustcgapi.jooq.tables.Game.GAME;
import static com.pegasus.pegasustcgapi.jooq.tables.LedgerEntry.LEDGER_ENTRY;
import static com.pegasus.pegasustcgapi.jooq.tables.Listing.LISTING;
import static com.pegasus.pegasustcgapi.jooq.tables.ListingSalePrice.LISTING_SALE_PRICE;
import static com.pegasus.pegasustcgapi.jooq.tables.OrderItem.ORDER_ITEM;
import static com.pegasus.pegasustcgapi.jooq.tables.PriceSale.PRICE_SALE;
import static com.pegasus.pegasustcgapi.jooq.tables.PriceSaleListing.PRICE_SALE_LISTING;
import static com.pegasus.pegasustcgapi.jooq.tables.SalesOrder.SALES_ORDER;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerOrder.SELLER_ORDER;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerProfile.SELLER_PROFILE;
import static com.pegasus.pegasustcgapi.jooq.tables.UserAccount.USER_ACCOUNT;

import com.pegasus.pegasustcgapi.common.Paging;
import com.pegasus.pegasustcgapi.dto.finance.AdminFinanceViews.Party;
import com.pegasus.pegasustcgapi.dto.sale.AdminSaleViews.AdminSale;
import com.pegasus.pegasustcgapi.dto.sale.AdminSaleViews.SaleListingRow;
import com.pegasus.pegasustcgapi.dto.sale.AdminSaleViews.ScopePreview;
import com.pegasus.pegasustcgapi.jooq.tables.SellerProfile;
import com.pegasus.pegasustcgapi.jooq.tables.UserAccount;
import com.pegasus.pegasustcgapi.model.catalog.CardCondition;
import com.pegasus.pegasustcgapi.model.listing.ListingStatus;
import com.pegasus.pegasustcgapi.model.sale.PriceSale;
import com.pegasus.pegasustcgapi.model.sale.PriceSale.OwnerType;
import com.pegasus.pegasustcgapi.model.sale.PriceSale.SaleType;
import com.pegasus.pegasustcgapi.model.sale.PriceSale.ScopeType;
import com.pegasus.pegasustcgapi.model.sale.SaleStatus;
import com.pegasus.pegasustcgapi.storage.StorageService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.SelectConditionStep;
import org.jooq.SelectOnConditionStep;
import org.jooq.SelectSelectStep;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.springframework.stereotype.Repository;

/**
 * Read side of the admin sales desk [TCG-677]. Writes stay in {@link PriceSaleRepository}.
 *
 * <p>Status is not a column, so filtering and the tab counts use {@link #status},
 * a CASE written to match {@link SaleStatus#of} rule for rule. SOLD_OUT there is a
 * flash sale that has sold at its price and no longer prices any listing.
 */
@Repository
public class AdminSaleRepository {

    private static final UserAccount OWNER = USER_ACCOUNT.as("owner");
    private static final UserAccount CREATOR = USER_ACCOUNT.as("creator");
    private static final UserAccount PAUSER = USER_ACCOUNT.as("pauser");
    private static final SellerProfile SHOP = SELLER_PROFILE.as("shop");

    /** Orders that count as paid for the KPI row; refunded and cancelled ones do not. */
    static final List<String> PAID = List.of("PAID", "PARTIALLY_COMPLETED", "COMPLETED");

    static final Field<Long> SOLD_UNITS = DSL.field(
            DSL.select(DSL.coalesce(DSL.sum(ORDER_ITEM.QUANTITY), 0).cast(SQLDataType.BIGINT))
                    .from(ORDER_ITEM)
                    .where(ORDER_ITEM.SALE_ID.eq(PRICE_SALE.ID)))
            .as("sold_units");

    static final Field<Long> LISTING_COUNT = DSL.field(
            DSL.selectCount().from(LISTING_SALE_PRICE).where(LISTING_SALE_PRICE.SALE_ID.eq(PRICE_SALE.ID))
                    .asField().cast(SQLDataType.BIGINT))
            .as("listing_count");

    private final DSLContext dsl;
    private final StorageService storage;

    public AdminSaleRepository(DSLContext dsl, StorageService storage) {
        this.dsl = dsl;
        this.storage = storage;
    }

    /** Newest first. {@code owner}, {@code type}, {@code status} and {@code q} are optional. */
    public List<AdminSale> page(OwnerType owner, SaleType type, SaleStatus status, String q, Paging paging,
            OffsetDateTime now) {
        Field<String> st = status(now).as("sale_status");
        return withOwner(dsl.select(PRICE_SALE.fields())
                        .select(OWNER.USERNAME, OWNER.DISPLAY_NAME, OWNER.AVATAR_URL)
                        .select(CREATOR.ID, CREATOR.USERNAME, CREATOR.DISPLAY_NAME, CREATOR.AVATAR_URL)
                        .select(PAUSER.ID, PAUSER.USERNAME, PAUSER.DISPLAY_NAME, PAUSER.AVATAR_URL)
                        .select(SOLD_UNITS, LISTING_COUNT, st))
                .where(filters(owner, type, q))
                .and(status == null ? DSL.noCondition() : status(now).eq(status.name()))
                .orderBy(PRICE_SALE.CREATED_AT.desc(), PRICE_SALE.ID.desc())
                .limit(paging.size())
                .offset(paging.offset())
                .fetch(r -> toView(r, st));
    }

    public long count(OwnerType owner, SaleType type, SaleStatus status, String q, OffsetDateTime now) {
        return withOwner(dsl.selectCount())
                .where(filters(owner, type, q))
                .and(status == null ? DSL.noCondition() : status(now).eq(status.name()))
                .fetchSingle(0, Long.class);
    }

    /** Every status, zero included, under the same owner, type and search filters. */
    public Map<SaleStatus, Long> statusCounts(OwnerType owner, SaleType type, String q, OffsetDateTime now) {
        Map<SaleStatus, Long> counts = new EnumMap<>(SaleStatus.class);
        for (SaleStatus s : SaleStatus.values()) counts.put(s, 0L);
        // Not "status": seller_profile has that column, and GROUP BY would read it first.
        Field<String> status = status(now).as("sale_status");
        withOwner(dsl.select(status, DSL.count()))
                .where(filters(owner, type, q))
                .groupBy(status)
                .fetch()
                .forEach(r -> counts.put(SaleStatus.valueOf(r.value1()), r.value2().longValue()));
        return counts;
    }

    public Optional<AdminSale> find(long id, OffsetDateTime now) {
        Field<String> st = status(now).as("sale_status");
        return withOwner(dsl.select(PRICE_SALE.fields())
                        .select(OWNER.USERNAME, OWNER.DISPLAY_NAME, OWNER.AVATAR_URL)
                        .select(CREATOR.ID, CREATOR.USERNAME, CREATOR.DISPLAY_NAME, CREATOR.AVATAR_URL)
                        .select(PAUSER.ID, PAUSER.USERNAME, PAUSER.DISPLAY_NAME, PAUSER.AVATAR_URL)
                        .select(SOLD_UNITS, LISTING_COUNT, st))
                .where(PRICE_SALE.ID.eq(id))
                .fetchOptional(r -> toView(r, st));
    }

    public long countActive(OwnerType owner, OffsetDateTime now) {
        return dsl.fetchCount(PRICE_SALE, PRICE_SALE.OWNER_TYPE.eq(owner.name())
                .and(status(now).eq(SaleStatus.ACTIVE.name())));
    }

    public long countListingsOnSale() {
        return dsl.fetchCount(LISTING_SALE_PRICE);
    }

    /** What buyers paid for cards sold at a sale price, in orders paid in [from, now]. */
    public BigDecimal saleSales(OffsetDateTime from) {
        return dsl.select(DSL.coalesce(DSL.sum(ORDER_ITEM.LINE_TOTAL), BigDecimal.ZERO))
                .from(ORDER_ITEM)
                .join(SELLER_ORDER).on(SELLER_ORDER.ID.eq(ORDER_ITEM.SELLER_ORDER_ID))
                .join(SALES_ORDER).on(SALES_ORDER.ID.eq(SELLER_ORDER.SALES_ORDER_ID))
                .where(ORDER_ITEM.SALE_ID.isNotNull())
                .and(SALES_ORDER.STATUS.in(PAID))
                .and(SALES_ORDER.PAID_AT.ge(from))
                .fetchSingle(0, BigDecimal.class);
    }

    /** SALE_SUBSIDY Pegasus paid out since {@code from}: the debit leg on its own wallet. */
    public BigDecimal saleSubsidy(OffsetDateTime from) {
        return dsl.select(DSL.coalesce(DSL.sum(LEDGER_ENTRY.AMOUNT), BigDecimal.ZERO))
                .from(LEDGER_ENTRY)
                .where(LEDGER_ENTRY.ENTRY_TYPE.eq("SALE_SUBSIDY"))
                .and(LEDGER_ENTRY.DIRECTION.eq("DEBIT"))
                .and(LEDGER_ENTRY.CREATED_AT.ge(from))
                .fetchSingle(0, BigDecimal.class);
    }

    /**
     * ACTIVE listings a scope reaches now — what {@code SalePriceResolver} would
     * price under a sale of that scope with no other sale in the way. A ฿1 card
     * is left out, since no discount can take it lower.
     */
    public ScopePreview previewScope(ScopeType scope, List<Long> ids, Collection<CardCondition> conditions,
            BigDecimal minUnitPrice) {
        Condition reach = LISTING.STATUS.eq(ListingStatus.ACTIVE.name())
                .and(LISTING.DELETED_AT.isNull())
                .and(LISTING.PRICE.gt(BigDecimal.ONE))
                .and(scopeCondition(scope, ids))
                .and(conditions.isEmpty() ? DSL.noCondition()
                        : LISTING.CONDITION_CODE.in(conditions.stream().map(Enum::name).toList()))
                .and(minUnitPrice == null ? DSL.noCondition() : LISTING.PRICE.ge(minUnitPrice));
        Record r = dsl.select(DSL.count(), DSL.countDistinct(LISTING.SELLER_PROFILE_ID),
                        DSL.coalesce(DSL.sum(LISTING.PRICE.mul(LISTING.QUANTITY_AVAILABLE)), BigDecimal.ZERO))
                .from(LISTING)
                .join(CATALOG_VARIANT).on(CATALOG_VARIANT.ID.eq(LISTING.CATALOG_VARIANT_ID))
                .join(CATALOG_PRODUCT).on(CATALOG_PRODUCT.ID.eq(CATALOG_VARIANT.CATALOG_PRODUCT_ID))
                .where(reach)
                .fetchSingle();
        return new ScopePreview(r.get(0, Long.class), r.get(1, Long.class), r.get(2, BigDecimal.class));
    }

    /** Whether every id names a game, category or card set; ALL has nothing to check. */
    public boolean scopeTargetsExist(ScopeType scope, List<Long> ids) {
        long distinct = ids.stream().distinct().count();
        return switch (scope) {
            case ALL, SHOP, LISTINGS -> ids.isEmpty();
            case GAME -> dsl.fetchCount(GAME, GAME.ID.cast(SQLDataType.BIGINT).in(ids)) == distinct;
            case CATEGORY -> dsl.fetchCount(CATALOG_CATEGORY,
                    CATALOG_CATEGORY.ID.cast(SQLDataType.BIGINT).in(ids)) == distinct;
            case CARD_SET -> dsl.fetchCount(CARD_SET, CARD_SET.ID.cast(SQLDataType.BIGINT).in(ids)) == distinct;
        };
    }

    /**
     * Listings a sale reaches: those selling at its price now, and those with a
     * price_sale_listing row (a flash listing whose quota ran out stays visible).
     */
    public List<SaleListingRow> listings(PriceSale sale, Paging paging) {
        return listingSelect(sale)
                .orderBy(LISTING.ID)
                .limit(paging.size())
                .offset(paging.offset())
                .fetch(r -> {
                    Integer quota = r.get(PRICE_SALE_LISTING.QUOTA) != null
                            ? r.get(PRICE_SALE_LISTING.QUOTA) : sale.perListingQuota();
                    int sold = r.get(PRICE_SALE_LISTING.SOLD_COUNT) == null ? 0 : r.get(PRICE_SALE_LISTING.SOLD_COUNT);
                    return new SaleListingRow(
                            r.get(LISTING.ID),
                            r.get(CATALOG_PRODUCT.NAME),
                            CardCondition.valueOf(r.get(LISTING.CONDITION_CODE)),
                            new Party(r.get(SHOP.ID), r.get(OWNER.USERNAME), r.get(OWNER.DISPLAY_NAME),
                                    avatar(r.get(OWNER.AVATAR_URL))),
                            r.get(LISTING.PRICE),
                            r.get(LISTING_SALE_PRICE.SALE_PRICE),
                            sold,
                            quota,
                            quota == null ? null : Math.max(quota - sold, 0));
                });
    }

    public long countListings(PriceSale sale) {
        return dsl.fetchCount(listingSelect(sale));
    }

    private SelectConditionStep<? extends Record> listingSelect(PriceSale sale) {
        return dsl.select(LISTING.ID, CATALOG_PRODUCT.NAME, LISTING.CONDITION_CODE, LISTING.PRICE,
                        SHOP.ID, OWNER.USERNAME, OWNER.DISPLAY_NAME, OWNER.AVATAR_URL,
                        LISTING_SALE_PRICE.SALE_PRICE, PRICE_SALE_LISTING.SOLD_COUNT, PRICE_SALE_LISTING.QUOTA)
                .from(LISTING)
                .join(CATALOG_VARIANT).on(CATALOG_VARIANT.ID.eq(LISTING.CATALOG_VARIANT_ID))
                .join(CATALOG_PRODUCT).on(CATALOG_PRODUCT.ID.eq(CATALOG_VARIANT.CATALOG_PRODUCT_ID))
                .join(SHOP).on(SHOP.ID.eq(LISTING.SELLER_PROFILE_ID))
                .join(OWNER).on(OWNER.ID.eq(SHOP.USER_ID))
                .leftJoin(LISTING_SALE_PRICE).on(LISTING_SALE_PRICE.LISTING_ID.eq(LISTING.ID)
                        .and(LISTING_SALE_PRICE.SALE_ID.eq(sale.id())))
                .leftJoin(PRICE_SALE_LISTING).on(PRICE_SALE_LISTING.LISTING_ID.eq(LISTING.ID)
                        .and(PRICE_SALE_LISTING.SALE_ID.eq(sale.id())))
                .where(LISTING_SALE_PRICE.LISTING_ID.isNotNull().or(PRICE_SALE_LISTING.LISTING_ID.isNotNull()));
    }

    /** The catalogue side of a scope, on LISTING joined to its variant and product. */
    static Condition scopeCondition(ScopeType scope, List<Long> ids) {
        return switch (scope) {
            case ALL, SHOP, LISTINGS -> DSL.noCondition();
            case GAME -> CATALOG_PRODUCT.GAME_ID.cast(SQLDataType.BIGINT).in(ids);
            case CATEGORY -> CATALOG_PRODUCT.CATEGORY_ID.cast(SQLDataType.BIGINT).in(ids);
            case CARD_SET -> CATALOG_PRODUCT.CARD_SET_ID.cast(SQLDataType.BIGINT).in(ids);
        };
    }

    /** {@link SaleStatus#of} in SQL: ended, then paused, scheduled, budget, sold out, else active. */
    static Field<String> status(OffsetDateTime now) {
        Field<OffsetDateTime> at = DSL.val(now);
        Condition ended = PRICE_SALE.ENDS_AT.le(at)
                .or(PRICE_SALE.ENDED_AT.isNotNull().and(PRICE_SALE.ENDED_AT.le(at)));
        Condition soldOut = PRICE_SALE.SALE_TYPE.eq(SaleType.FLASH_SALE.name())
                .andNotExists(DSL.selectOne().from(LISTING_SALE_PRICE)
                        .where(LISTING_SALE_PRICE.SALE_ID.eq(PRICE_SALE.ID)))
                .andExists(DSL.selectOne().from(PRICE_SALE_LISTING)
                        .where(PRICE_SALE_LISTING.SALE_ID.eq(PRICE_SALE.ID))
                        .and(PRICE_SALE_LISTING.SOLD_COUNT.gt(0)));
        return DSL.when(ended, SaleStatus.ENDED.name())
                .when(PRICE_SALE.PAUSED_AT.isNotNull(), SaleStatus.PAUSED.name())
                .when(PRICE_SALE.STARTS_AT.gt(at), SaleStatus.SCHEDULED.name())
                .when(PRICE_SALE.BUDGET_CAP.isNotNull()
                        .and(PRICE_SALE.SUBSIDY_RESERVED.ge(PRICE_SALE.BUDGET_CAP)), SaleStatus.BUDGET_EXHAUSTED.name())
                .when(soldOut, SaleStatus.SOLD_OUT.name())
                .otherwise(SaleStatus.ACTIVE.name());
    }

    private static <R extends Record> SelectOnConditionStep<R> withOwner(SelectSelectStep<R> select) {
        return select.from(PRICE_SALE)
                .leftJoin(SELLER_PROFILE).on(SELLER_PROFILE.ID.eq(PRICE_SALE.SELLER_PROFILE_ID))
                .leftJoin(OWNER).on(OWNER.ID.eq(SELLER_PROFILE.USER_ID))
                .leftJoin(CREATOR).on(CREATOR.ID.eq(PRICE_SALE.CREATED_BY))
                .leftJoin(PAUSER).on(PAUSER.ID.eq(PRICE_SALE.PAUSED_BY));
    }

    /** {@code q} matches the name, the slug, or the shop's username or display name. */
    private static Condition filters(OwnerType owner, SaleType type, String q) {
        Condition c = owner == null ? DSL.noCondition() : PRICE_SALE.OWNER_TYPE.eq(owner.name());
        if (type != null) c = c.and(PRICE_SALE.SALE_TYPE.eq(type.name()));
        if (q != null && !q.isBlank()) {
            String like = "%" + q.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            c = c.and(PRICE_SALE.NAME.likeIgnoreCase(like, '\\')
                    .or(PRICE_SALE.SLUG.likeIgnoreCase(like, '\\'))
                    .or(OWNER.USERNAME.likeIgnoreCase(like, '\\'))
                    .or(OWNER.DISPLAY_NAME.likeIgnoreCase(like, '\\')));
        }
        return c;
    }

    private AdminSale toView(Record r, Field<String> status) {
        PriceSale s = PriceSaleRepository.toSale(r.into(PRICE_SALE));
        Party owner = s.sellerProfileId() == null ? null : new Party(s.sellerProfileId(),
                r.get(OWNER.USERNAME), r.get(OWNER.DISPLAY_NAME), avatar(r.get(OWNER.AVATAR_URL)));
        return new AdminSale(s.id(), s.name(), s.slug(), s.ownerType(), owner, s.saleType(),
                s.discountType(), s.discountValue(), s.maxDiscountPerUnit(), s.scopeType(), s.scopeIds(),
                s.conditions(), s.minUnitPrice(), s.perListingQuota(), s.perBuyerLimit(), s.budgetCap(),
                s.subsidyReserved(), s.bannerImageUrl(), s.featuredOnHome(), s.homeSort(), s.startsAt(),
                s.endsAt(), s.endedAt(), SaleStatus.valueOf(r.get(status)), s.pausedAt(), s.pauseReason(),
                party(r, PAUSER), party(r, CREATOR), s.createdAt(), s.updatedAt(),
                r.get(SOLD_UNITS), r.get(LISTING_COUNT));
    }

    private Party party(Record r, UserAccount user) {
        Long id = r.get(user.ID);
        return id == null ? null
                : new Party(id, r.get(user.USERNAME), r.get(user.DISPLAY_NAME), avatar(r.get(user.AVATAR_URL)));
    }

    /** {@code user_account.avatar_url} holds an object key; the page needs a short-lived link. */
    private String avatar(String key) {
        return key == null ? null : storage.presignDownload(key);
    }
}
