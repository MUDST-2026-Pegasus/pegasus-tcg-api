package com.pegasus.pegasustcgapi.repository.order;

import static com.pegasus.pegasustcgapi.jooq.tables.Auction.AUCTION;
import static com.pegasus.pegasustcgapi.jooq.tables.CatalogImage.CATALOG_IMAGE;
import static com.pegasus.pegasustcgapi.jooq.tables.CatalogProduct.CATALOG_PRODUCT;
import static com.pegasus.pegasustcgapi.jooq.tables.CatalogVariant.CATALOG_VARIANT;
import static com.pegasus.pegasustcgapi.jooq.tables.Game.GAME;
import static com.pegasus.pegasustcgapi.jooq.tables.Listing.LISTING;
import static com.pegasus.pegasustcgapi.jooq.tables.ListingUnit.LISTING_UNIT;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerProfile.SELLER_PROFILE;
import static com.pegasus.pegasustcgapi.jooq.tables.UserAccount.USER_ACCOUNT;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/**
 * Read-only lookups an order shows or snapshots next to its own rows: the card behind a
 * listing or auction, shop names, and catalogue data for re-ordering.
 */
@Repository
public class OrderReferenceQueryRepository {

    private final DSLContext dsl;

    public OrderReferenceQueryRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Map<Long, String> findAuctionNumbers(Collection<Long> auctionIds) {
        if (auctionIds.isEmpty()) return Map.of();
        return dsl.select(AUCTION.ID, AUCTION.AUCTION_NUMBER).from(AUCTION)
                .where(AUCTION.ID.in(auctionIds)).fetchMap(AUCTION.ID, AUCTION.AUCTION_NUMBER);
    }

    public record ListingSnapshotDetails(
            Long listingId,
            long catalogVariantId,
            String condition,
            String productName,
            String variantLabel,
            String gameName,
            String imageKey) {
    }

    public Map<Long, ListingSnapshotDetails> findListingDetails(Collection<Long> listingIds) {
        if (listingIds.isEmpty()) {
            return Map.of();
        }
        return dsl.select(
                LISTING.ID,
                LISTING.CATALOG_VARIANT_ID,
                LISTING.CONDITION_CODE,
                CATALOG_PRODUCT.NAME,
                CATALOG_VARIANT.LANGUAGE_CODE,
                CATALOG_VARIANT.FINISH,
                CATALOG_VARIANT.EDITION,
                CATALOG_VARIANT.PRINTING_NOTE,
                GAME.NAME,
                CATALOG_IMAGE.IMAGE_KEY)
                .from(LISTING)
                .join(CATALOG_VARIANT).on(CATALOG_VARIANT.ID.eq(LISTING.CATALOG_VARIANT_ID))
                .join(CATALOG_PRODUCT).on(CATALOG_PRODUCT.ID.eq(CATALOG_VARIANT.CATALOG_PRODUCT_ID))
                .leftJoin(GAME).on(GAME.ID.eq(CATALOG_PRODUCT.GAME_ID))
                .leftJoin(CATALOG_IMAGE).on(CATALOG_IMAGE.CATALOG_PRODUCT_ID.eq(CATALOG_PRODUCT.ID)
                        .and(CATALOG_IMAGE.IS_PRIMARY.isTrue()))
                .where(LISTING.ID.in(listingIds))
                .fetchMap(LISTING.ID, r -> {
                    String languageCode = r.get(CATALOG_VARIANT.LANGUAGE_CODE);
                    String finish = r.get(CATALOG_VARIANT.FINISH);
                    String edition = r.get(CATALOG_VARIANT.EDITION);
                    String printingNote = r.get(CATALOG_VARIANT.PRINTING_NOTE);
                    StringBuilder label = new StringBuilder(languageCode != null ? languageCode : "EN");
                    if (finish != null && !"NOT_APPLICABLE".equals(finish)) {
                        label.append(" / ").append(finish);
                    }
                    if (edition != null && !"NOT_APPLICABLE".equals(edition)) {
                        label.append(" / ").append(edition);
                    }
                    if (printingNote != null && !printingNote.isBlank()) {
                        label.append(" / ").append(printingNote);
                    }
                    return new ListingSnapshotDetails(
                            r.get(LISTING.ID),
                            r.get(LISTING.CATALOG_VARIANT_ID),
                            r.get(LISTING.CONDITION_CODE),
                            r.get(CATALOG_PRODUCT.NAME),
                            label.toString(),
                            r.get(GAME.NAME),
                            r.get(CATALOG_IMAGE.IMAGE_KEY));
                });
    }

    /** The same order-item snapshot for a physical card removed from its listing for auction. */
    public Optional<ListingSnapshotDetails> findAuctionCardDetails(long unitId) {
        return dsl.select(LISTING_UNIT.LISTING_ID, LISTING_UNIT.CATALOG_VARIANT_ID,
                        LISTING_UNIT.CONDITION_CODE, CATALOG_PRODUCT.NAME,
                        CATALOG_VARIANT.LANGUAGE_CODE, CATALOG_VARIANT.FINISH,
                        CATALOG_VARIANT.EDITION, CATALOG_VARIANT.PRINTING_NOTE,
                        GAME.NAME, CATALOG_IMAGE.IMAGE_KEY)
                .from(LISTING_UNIT)
                .join(CATALOG_VARIANT).on(CATALOG_VARIANT.ID.eq(LISTING_UNIT.CATALOG_VARIANT_ID))
                .join(CATALOG_PRODUCT).on(CATALOG_PRODUCT.ID.eq(CATALOG_VARIANT.CATALOG_PRODUCT_ID))
                .leftJoin(GAME).on(GAME.ID.eq(CATALOG_PRODUCT.GAME_ID))
                .leftJoin(CATALOG_IMAGE).on(CATALOG_IMAGE.CATALOG_PRODUCT_ID.eq(CATALOG_PRODUCT.ID)
                        .and(CATALOG_IMAGE.IS_PRIMARY.isTrue()))
                .where(LISTING_UNIT.ID.eq(unitId))
                .fetchOptional(r -> {
                    StringBuilder label = new StringBuilder(
                            r.get(CATALOG_VARIANT.LANGUAGE_CODE) == null ? "EN" : r.get(CATALOG_VARIANT.LANGUAGE_CODE));
                    for (String part : new String[]{r.get(CATALOG_VARIANT.FINISH),
                            r.get(CATALOG_VARIANT.EDITION), r.get(CATALOG_VARIANT.PRINTING_NOTE)}) {
                        if (part != null && !part.isBlank() && !"NOT_APPLICABLE".equals(part)) {
                            label.append(" / ").append(part);
                        }
                    }
                    return new ListingSnapshotDetails(r.get(LISTING_UNIT.LISTING_ID),
                            r.get(LISTING_UNIT.CATALOG_VARIANT_ID), r.get(LISTING_UNIT.CONDITION_CODE),
                            r.get(CATALOG_PRODUCT.NAME), label.toString(), r.get(GAME.NAME),
                            r.get(CATALOG_IMAGE.IMAGE_KEY));
                });
    }

    public Map<Long, String> findSellerNamesByProfileIds(Collection<Long> sellerProfileIds) {
        if (sellerProfileIds.isEmpty()) {
            return Map.of();
        }
        return dsl.select(SELLER_PROFILE.ID, DSL.coalesce(USER_ACCOUNT.DISPLAY_NAME, USER_ACCOUNT.USERNAME))
                .from(SELLER_PROFILE)
                .join(USER_ACCOUNT).on(USER_ACCOUNT.ID.eq(SELLER_PROFILE.USER_ID))
                .where(SELLER_PROFILE.ID.in(sellerProfileIds))
                .fetchMap(SELLER_PROFILE.ID, r -> r.value2());
    }

    public Map<Long, SellerIdentity> findSellerIdentitiesByProfileIds(Collection<Long> sellerProfileIds) {
        if (sellerProfileIds.isEmpty()) {
            return Map.of();
        }
        return dsl.select(SELLER_PROFILE.ID, USER_ACCOUNT.USERNAME, USER_ACCOUNT.DISPLAY_NAME)
                .from(SELLER_PROFILE)
                .join(USER_ACCOUNT).on(USER_ACCOUNT.ID.eq(SELLER_PROFILE.USER_ID))
                .where(SELLER_PROFILE.ID.in(sellerProfileIds))
                .fetchMap(SELLER_PROFILE.ID,
                        row -> new SellerIdentity(row.value2(), row.value3()));
    }

    public record SellerIdentity(String username, String displayName) {
    }

    public record ReorderProductMetadata(String productSlug, String rarity) {}

    public Map<Long, ReorderProductMetadata> findReorderProductMetadata(Collection<Long> catalogVariantIds) {
        if (catalogVariantIds.isEmpty()) {
            return Map.of();
        }
        return dsl.select(CATALOG_VARIANT.ID, CATALOG_PRODUCT.SLUG, CATALOG_PRODUCT.RARITY_CODE)
                .from(CATALOG_VARIANT)
                .join(CATALOG_PRODUCT).on(CATALOG_PRODUCT.ID.eq(CATALOG_VARIANT.CATALOG_PRODUCT_ID))
                .where(CATALOG_VARIANT.ID.in(catalogVariantIds))
                .fetchMap(CATALOG_VARIANT.ID, r -> new ReorderProductMetadata(
                        r.get(CATALOG_PRODUCT.SLUG),
                        r.get(CATALOG_PRODUCT.RARITY_CODE)));
    }
}
