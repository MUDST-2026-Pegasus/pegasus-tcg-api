package com.pegasus.pegasustcgapi.repository.listing;

import static com.pegasus.pegasustcgapi.jooq.tables.CatalogVariant.CATALOG_VARIANT;
import static com.pegasus.pegasustcgapi.jooq.tables.Listing.LISTING;
import static com.pegasus.pegasustcgapi.jooq.tables.ListingSalePrice.LISTING_SALE_PRICE;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerProfile.SELLER_PROFILE;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerRatingSummary.SELLER_RATING_SUMMARY;
import static com.pegasus.pegasustcgapi.jooq.tables.UserAccount.USER_ACCOUNT;

import com.pegasus.pegasustcgapi.dto.listing.AdminCardListingResponse.AdminCardListingSummary;
import com.pegasus.pegasustcgapi.jooq.tables.UserAccount;
import com.pegasus.pegasustcgapi.model.listing.Listing;
import com.pegasus.pegasustcgapi.model.listing.ListingStatus;
import com.pegasus.pegasustcgapi.model.seller.SellerStatus;
import com.pegasus.pegasustcgapi.model.user.UserStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.SelectField;
import org.jooq.SelectOnConditionStep;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/**
 * The back office's view of every shop's listings of one card, and the admin's
 * block on one of them. Only ever touches the listing row, so it can lock it
 * without the cards first; see {@link ListingRepository}.
 */
@Repository
public class AdminCardListingRepository {

    private static final Field<String> SELLER_STATUS = SELLER_PROFILE.STATUS.as("seller_status");
    private static final Field<UserStatus> ACCOUNT_STATUS = USER_ACCOUNT.STATUS.as("account_status");
    private static final Field<OffsetDateTime> ACCOUNT_DELETED_AT = USER_ACCOUNT.DELETED_AT.as("account_deleted_at");

    private static final UserAccount BLOCKER = USER_ACCOUNT.as("blocker");
    private static final Field<String> BLOCKED_BY_USERNAME = DSL.field(
            DSL.select(BLOCKER.USERNAME).from(BLOCKER).where(BLOCKER.ID.eq(LISTING.BLOCKED_BY)))
            .as("blocked_by_username");

    private static final Field<Integer> STATUS_RANK = DSL.case_(LISTING.STATUS)
            .when(ListingStatus.ACTIVE.name(), 0)
            .when(ListingStatus.PAUSED.name(), 1)
            .when(ListingStatus.SOLD_OUT.name(), 2)
            .when(ListingStatus.BLOCKED.name(), 3)
            .when(ListingStatus.DRAFT.name(), 4)
            .otherwise(5);

    /** A listing that is or was on the market: what "the shops selling this card" means. */
    private static final Condition IN_SCOPE = LISTING.DELETED_AT.isNull()
            .and(LISTING.STATUS.notIn(ListingStatus.DRAFT.name(), ListingStatus.DELISTED.name()));

    private final DSLContext dsl;

    public AdminCardListingRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public record Query(long productId, String search, Long variantId, ListingStatus status,
                        int limit, int offset, OffsetDateTime now) {
    }

    /**
     * @param offer     the listing and the standing of its shop
     * @param salePrice null while no sale runs
     */
    public record Row(
            ListingRepository.Offer offer,
            long sellerUserId,
            String avatarKey,
            BigDecimal ratingAvg,
            int ratingCount,
            BigDecimal salePrice,
            OffsetDateTime saleEndsAt,
            String blockedByUsername) {
    }

    public record ProductStock(int sellerCount, int availableQuantity, int blockedListingCount) {
        public static final ProductStock NONE = new ProductStock(0, 0, 0);
    }

    /** What can be bought first, then by how much is left, so the shops that matter are on page one. */
    public List<Row> find(Query query) {
        return select(query.now())
                .where(conditions(query))
                .orderBy(STATUS_RANK, LISTING.QUANTITY_AVAILABLE.desc(), LISTING.ID)
                .limit(query.limit())
                .offset(query.offset())
                .fetch(AdminCardListingRepository::toRow);
    }

    public long count(Query query) {
        return dsl.selectCount()
                .from(joined())
                .where(conditions(query))
                .fetchSingle(0, long.class);
    }

    public Optional<Row> findOne(long listingId, OffsetDateTime now) {
        return select(now)
                .where(LISTING.ID.eq(listingId))
                .and(LISTING.DELETED_AT.isNull())
                .fetchOptional(AdminCardListingRepository::toRow);
    }

    public AdminCardListingSummary summary(long productId) {
        Condition purchasable = ListingRepository.purchasableNow();
        Field<Integer> listingCount = DSL.count().as("listing_count");
        Field<Integer> shopCount = DSL.countDistinct(LISTING.SELLER_PROFILE_ID).as("shop_count");
        Field<Integer> available = sum(LISTING.QUANTITY_AVAILABLE, purchasable).as("available");
        Field<Integer> availableShops = DSL.countDistinct(LISTING.SELLER_PROFILE_ID).filterWhere(purchasable)
                .as("available_shops");
        Field<Integer> reserved = sum(LISTING.QUANTITY_RESERVED, DSL.trueCondition()).as("reserved");
        Field<Integer> paused = sum(LISTING.QUANTITY_AVAILABLE, LISTING.STATUS.eq(ListingStatus.PAUSED.name()))
                .as("paused");
        Field<Integer> blocked = sum(LISTING.QUANTITY_AVAILABLE, LISTING.STATUS.eq(ListingStatus.BLOCKED.name()))
                .as("blocked");
        Field<Integer> blockedListings = DSL.count().filterWhere(LISTING.STATUS.eq(ListingStatus.BLOCKED.name()))
                .as("blocked_listings");
        Field<Integer> closedShop = sum(LISTING.QUANTITY_AVAILABLE, LISTING.STATUS.eq(ListingStatus.ACTIVE.name())
                .andNot(ListingRepository.sellerProfileOpenForBusiness())).as("closed_shop");

        Record r = dsl.select(listingCount, shopCount, available, availableShops, reserved, paused, blocked,
                        blockedListings, closedShop)
                .from(joined())
                .where(CATALOG_VARIANT.CATALOG_PRODUCT_ID.eq(productId))
                .and(IN_SCOPE)
                .fetchSingle();
        return new AdminCardListingSummary(r.get(shopCount), r.get(listingCount), r.get(available), r.get(availableShops),
                r.get(reserved), r.get(paused), r.get(blocked), r.get(blockedListings), r.get(closedShop));
    }

    /** One grouped read for the whole page of tiles. A product nobody lists is absent. */
    public Map<Long, ProductStock> stockByProduct(Collection<Long> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        Condition purchasable = ListingRepository.purchasableNow();
        Field<Integer> sellers = DSL.countDistinct(LISTING.SELLER_PROFILE_ID).filterWhere(purchasable).as("sellers");
        Field<Integer> available = sum(LISTING.QUANTITY_AVAILABLE, purchasable).as("available");
        Field<Integer> blocked = DSL.count().filterWhere(LISTING.STATUS.eq(ListingStatus.BLOCKED.name()))
                .as("blocked");
        return dsl.select(CATALOG_VARIANT.CATALOG_PRODUCT_ID, sellers, available, blocked)
                .from(joined())
                .where(CATALOG_VARIANT.CATALOG_PRODUCT_ID.in(productIds))
                .and(LISTING.DELETED_AT.isNull())
                .groupBy(CATALOG_VARIANT.CATALOG_PRODUCT_ID)
                .fetchMap(CATALOG_VARIANT.CATALOG_PRODUCT_ID,
                        r -> new ProductStock(r.get(sellers), r.get(available), r.get(blocked)));
    }

    /** Held to the end of the transaction, so the trigger cannot flip ACTIVE and SOLD_OUT under a block. */
    public Optional<Listing> lock(long listingId) {
        return dsl.selectFrom(LISTING)
                .where(LISTING.ID.eq(listingId))
                .and(LISTING.DELETED_AT.isNull())
                .forNoKeyUpdate()
                .fetchOptional(ListingRepository::toListing);
    }

    public void block(long listingId, ListingStatus from, long adminId, String reason, OffsetDateTime at) {
        dsl.update(LISTING)
                .set(LISTING.STATUS, ListingStatus.BLOCKED.name())
                .set(LISTING.BLOCKED_REASON, reason)
                .set(LISTING.BLOCKED_BY, adminId)
                .set(LISTING.BLOCKED_AT, at)
                .set(LISTING.BLOCKED_FROM_STATUS, from.name())
                .where(LISTING.ID.eq(listingId))
                .execute();
    }

    /** Back to a status that does not depend on the stock. */
    public void unblockTo(long listingId, ListingStatus to) {
        dsl.update(LISTING)
                .set(LISTING.STATUS, to.name())
                .setNull(LISTING.BLOCKED_REASON)
                .setNull(LISTING.BLOCKED_BY)
                .setNull(LISTING.BLOCKED_AT)
                .setNull(LISTING.BLOCKED_FROM_STATUS)
                .where(LISTING.ID.eq(listingId))
                .execute();
    }

    /** Back on the market: the row decides, since cards may have sold or come back while it was blocked. */
    public void unblockToMarket(long listingId, OffsetDateTime at) {
        dsl.update(LISTING)
                .set(LISTING.STATUS, DSL.when(LISTING.QUANTITY_AVAILABLE.gt(0), ListingStatus.ACTIVE.name())
                        .otherwise(ListingStatus.SOLD_OUT.name()))
                .set(LISTING.PUBLISHED_AT, DSL.coalesce(LISTING.PUBLISHED_AT, DSL.val(at)))
                .setNull(LISTING.BLOCKED_REASON)
                .setNull(LISTING.BLOCKED_BY)
                .setNull(LISTING.BLOCKED_AT)
                .setNull(LISTING.BLOCKED_FROM_STATUS)
                .where(LISTING.ID.eq(listingId))
                .execute();
    }

    private static Field<Integer> sum(Field<Integer> field, Condition filter) {
        return DSL.coalesce(DSL.sum(field).filterWhere(filter), DSL.inline(BigDecimal.ZERO)).cast(Integer.class);
    }

    private static Table<?> joined() {
        return LISTING
                .join(CATALOG_VARIANT).on(CATALOG_VARIANT.ID.eq(LISTING.CATALOG_VARIANT_ID))
                .join(SELLER_PROFILE).on(SELLER_PROFILE.ID.eq(LISTING.SELLER_PROFILE_ID))
                .join(USER_ACCOUNT).on(USER_ACCOUNT.ID.eq(SELLER_PROFILE.USER_ID));
    }

    private SelectOnConditionStep<Record> select(OffsetDateTime now) {
        List<SelectField<?>> fields = new ArrayList<>(List.of(LISTING.fields()));
        fields.add(SELLER_STATUS);
        fields.add(SELLER_PROFILE.VACATION_MODE);
        fields.add(SELLER_PROFILE.USER_ID);
        fields.add(ACCOUNT_STATUS);
        fields.add(ACCOUNT_DELETED_AT);
        fields.add(USER_ACCOUNT.USERNAME);
        fields.add(USER_ACCOUNT.DISPLAY_NAME);
        fields.add(USER_ACCOUNT.AVATAR_URL);
        fields.add(SELLER_RATING_SUMMARY.RATING_AVG);
        fields.add(SELLER_RATING_SUMMARY.RATING_COUNT);
        fields.add(LISTING_SALE_PRICE.SALE_PRICE);
        fields.add(LISTING_SALE_PRICE.ENDS_AT);
        fields.add(BLOCKED_BY_USERNAME);

        return dsl.select(fields)
                .from(joined())
                .leftJoin(SELLER_RATING_SUMMARY).on(SELLER_RATING_SUMMARY.SELLER_PROFILE_ID.eq(SELLER_PROFILE.ID))
                .leftJoin(LISTING_SALE_PRICE).on(ListingRepository.salePriceRunning(now));
    }

    private static Condition conditions(Query query) {
        Condition condition = CATALOG_VARIANT.CATALOG_PRODUCT_ID.eq(query.productId())
                .and(query.status() == null
                        ? IN_SCOPE
                        : LISTING.DELETED_AT.isNull().and(LISTING.STATUS.eq(query.status().name())));
        if (query.variantId() != null) {
            condition = condition.and(LISTING.CATALOG_VARIANT_ID.eq(query.variantId()));
        }
        if (query.search() != null && !query.search().isBlank()) {
            condition = condition.and(searchMatches(query.search().trim()));
        }
        return condition;
    }

    /** The shop's names, or the listing's number with or without the # it is shown with. */
    private static Condition searchMatches(String text) {
        String pattern = "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        Condition match = DSL.condition("{0} ILIKE {1} ESCAPE '\\'", USER_ACCOUNT.USERNAME, DSL.val(pattern))
                .or(DSL.condition("{0} ILIKE {1} ESCAPE '\\'", USER_ACCOUNT.DISPLAY_NAME, DSL.val(pattern)));
        String number = text.startsWith("#") ? text.substring(1) : text;
        if (!number.matches("\\d{1,18}")) {
            return match;
        }
        try {
            return match.or(LISTING.ID.eq(Long.parseLong(number)));
        } catch (NumberFormatException e) {
            return match;
        }
    }

    private static Row toRow(Record r) {
        Integer ratingCount = r.get(SELLER_RATING_SUMMARY.RATING_COUNT);
        boolean rated = ratingCount != null && ratingCount > 0;
        return new Row(
                new ListingRepository.Offer(
                        ListingRepository.toListing(r.into(LISTING)),
                        SellerStatus.valueOf(r.get(SELLER_STATUS)),
                        r.get(SELLER_PROFILE.VACATION_MODE),
                        r.get(ACCOUNT_STATUS) == UserStatus.ACTIVE && r.get(ACCOUNT_DELETED_AT) == null,
                        r.get(USER_ACCOUNT.DISPLAY_NAME),
                        r.get(USER_ACCOUNT.USERNAME)),
                r.get(SELLER_PROFILE.USER_ID),
                r.get(USER_ACCOUNT.AVATAR_URL),
                rated ? r.get(SELLER_RATING_SUMMARY.RATING_AVG) : null,
                rated ? ratingCount : 0,
                r.get(LISTING_SALE_PRICE.SALE_PRICE),
                r.get(LISTING_SALE_PRICE.ENDS_AT),
                r.get(BLOCKED_BY_USERNAME));
    }
}
