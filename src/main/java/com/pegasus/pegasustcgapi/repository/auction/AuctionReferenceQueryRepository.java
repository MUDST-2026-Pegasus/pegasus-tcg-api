package com.pegasus.pegasustcgapi.repository.auction;

import static com.pegasus.pegasustcgapi.jooq.tables.Listing.LISTING;
import static com.pegasus.pegasustcgapi.jooq.tables.Auction.AUCTION;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerOrder.SELLER_ORDER;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerProfile.SELLER_PROFILE;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerRatingSummary.SELLER_RATING_SUMMARY;
import static com.pegasus.pegasustcgapi.jooq.tables.UserAccount.USER_ACCOUNT;

import com.pegasus.pegasustcgapi.dto.auction.SellerAuctionViews.MarketGuide;
import com.pegasus.pegasustcgapi.model.listing.ListingStatus;
import java.util.Collection;
import java.util.HashMap;
import com.pegasus.pegasustcgapi.model.auction.AuctionStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/**
 * Read-only lookups that auction pages show next to an auction: the shop, usernames, market
 * price guides and which cards are held by an open auction.
 */
@Repository
public class AuctionReferenceQueryRepository {

    private final DSLContext dsl;

    public AuctionReferenceQueryRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public long getSellerCompletedSalesCount(long sellerProfileId) {
        return dsl.fetchCount(SELLER_ORDER,
                SELLER_ORDER.SELLER_PROFILE_ID.eq(sellerProfileId)
                        .and(SELLER_ORDER.STATUS.ne("CANCELLED")));
    }

    public record SellerInfo(String shopName, String username, BigDecimal rating) {}

    public Optional<SellerInfo> findSellerInfo(long sellerProfileId) {
        var row = dsl.select(
                USER_ACCOUNT.DISPLAY_NAME,
                USER_ACCOUNT.USERNAME,
                SELLER_RATING_SUMMARY.RATING_AVG)
                .from(SELLER_PROFILE)
                .join(USER_ACCOUNT).on(USER_ACCOUNT.ID.eq(SELLER_PROFILE.USER_ID))
                .leftJoin(SELLER_RATING_SUMMARY).on(SELLER_RATING_SUMMARY.SELLER_PROFILE_ID.eq(SELLER_PROFILE.ID))
                .where(SELLER_PROFILE.ID.eq(sellerProfileId))
                .fetchOne();

        if (row == null) {
            return Optional.empty();
        }
        String shopName = row.get(USER_ACCOUNT.DISPLAY_NAME) != null ? row.get(USER_ACCOUNT.DISPLAY_NAME)
                : (row.get(USER_ACCOUNT.USERNAME) != null ? row.get(USER_ACCOUNT.USERNAME) : "Shop");
        String username = row.get(USER_ACCOUNT.USERNAME) != null ? row.get(USER_ACCOUNT.USERNAME) : "seller";
        BigDecimal rating = row.get(SELLER_RATING_SUMMARY.RATING_AVG);
        return Optional.of(new SellerInfo(shopName, username, rating));
    }

    public Map<Long, String> findUsernames(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return java.util.Map.of();
        }
        return dsl.select(USER_ACCOUNT.ID, USER_ACCOUNT.USERNAME)
                .from(USER_ACCOUNT)
                .where(USER_ACCOUNT.ID.in(userIds))
                .fetchMap(USER_ACCOUNT.ID, USER_ACCOUNT.USERNAME);
    }

    public Optional<String> findUsername(long userId) {
        return Optional.ofNullable(findUsernames(List.of(userId)).get(userId));
    }

    public record VariantConditionKey(long catalogVariantId, String conditionCode) {}

    public Map<VariantConditionKey, MarketGuide> findMarketGuides(Collection<Long> catalogVariantIds) {
        if (catalogVariantIds == null || catalogVariantIds.isEmpty()) {
            return Map.of();
        }

        var medianField = DSL.field("percentile_disc(0.5) WITHIN GROUP (ORDER BY {0})", BigDecimal.class, LISTING.PRICE);
        var countField = DSL.count();
        var minField = DSL.min(LISTING.PRICE);
        var maxField = DSL.max(LISTING.PRICE);

        var records = dsl.select(
                LISTING.CATALOG_VARIANT_ID,
                LISTING.CONDITION_CODE,
                countField,
                minField,
                maxField,
                medianField)
                .from(LISTING)
                .where(LISTING.CATALOG_VARIANT_ID.in(catalogVariantIds))
                .and(LISTING.STATUS.eq(ListingStatus.ACTIVE.name()))
                .and(LISTING.DELETED_AT.isNull())
                .groupBy(LISTING.CATALOG_VARIANT_ID, LISTING.CONDITION_CODE)
                .fetch();

        Map<VariantConditionKey, MarketGuide> result = new HashMap<>();
        for (var r : records) {
            long variantId = r.get(LISTING.CATALOG_VARIANT_ID);
            String condition = r.get(LISTING.CONDITION_CODE);
            int count = r.get(countField);
            BigDecimal min = r.get(minField);
            BigDecimal max = r.get(maxField);
            BigDecimal median = r.get(medianField);
            result.put(new VariantConditionKey(variantId, condition), new MarketGuide(count, min, median, max));
        }
        return result;
    }

    public Map<Long, String> findHeldAuctionNumbers(Collection<Long> listingUnitIds) {
        if (listingUnitIds == null || listingUnitIds.isEmpty()) {
            return Map.of();
        }
        var records = dsl.select(AUCTION.LISTING_UNIT_ID, AUCTION.AUCTION_NUMBER)
                .distinctOn(AUCTION.LISTING_UNIT_ID)
                .from(AUCTION)
                .where(AUCTION.LISTING_UNIT_ID.in(listingUnitIds))
                .and(AUCTION.STATUS.in(
                        AuctionStatus.LIVE.name(),
                        AuctionStatus.AWAITING_PAYMENT.name()))
                .orderBy(AUCTION.LISTING_UNIT_ID, AUCTION.CREATED_AT.desc())
                .fetch();

        Map<Long, String> result = new HashMap<>();
        for (var r : records) {
            result.put(r.get(AUCTION.LISTING_UNIT_ID), r.get(AUCTION.AUCTION_NUMBER));
        }
        return result;
    }

    public Map<Long, String> findShopNames(Collection<Long> sellerProfileIds) {
        if (sellerProfileIds == null || sellerProfileIds.isEmpty()) {
            return Map.of();
        }
        return dsl.select(SELLER_PROFILE.ID, USER_ACCOUNT.DISPLAY_NAME, USER_ACCOUNT.USERNAME)
                .from(SELLER_PROFILE)
                .join(USER_ACCOUNT).on(USER_ACCOUNT.ID.eq(SELLER_PROFILE.USER_ID))
                .where(SELLER_PROFILE.ID.in(sellerProfileIds))
                .fetchMap(
                        r -> r.get(SELLER_PROFILE.ID),
                        r -> {
                            String displayName = r.get(USER_ACCOUNT.DISPLAY_NAME);
                            if (displayName != null && !displayName.isBlank()) {
                                return displayName;
                            }
                            String username = r.get(USER_ACCOUNT.USERNAME);
                            return username != null && !username.isBlank() ? username : "Shop";
                        }
                );
    }
}
