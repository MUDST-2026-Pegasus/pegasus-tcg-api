package com.pegasus.pegasustcgapi.repository;

import static com.pegasus.pegasustcgapi.jooq.tables.Listing.LISTING;
import static com.pegasus.pegasustcgapi.jooq.tables.ListingImage.LISTING_IMAGE;

import com.pegasus.pegasustcgapi.jooq.tables.records.ListingImageRecord;
import com.pegasus.pegasustcgapi.model.ListingImage;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.InsertValuesStep4;
import org.springframework.stereotype.Repository;

/** Reads and writes {@code listing_image}. Rows hold an object key, never a public URL. */
@Repository
public class ListingImageRepository {

    private final DSLContext dsl;

    public ListingImageRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public List<ListingImage> findByListing(long listingId) {
        return dsl.selectFrom(LISTING_IMAGE)
                .where(LISTING_IMAGE.LISTING_ID.eq(listingId))
                .orderBy(LISTING_IMAGE.SORT_ORDER, LISTING_IMAGE.ID)
                .fetch(ListingImageRepository::toImage);
    }

    /** The first photo of each listing named, for a page of tiles in one query. */
    public Map<Long, String> primaryKeysOf(Collection<Long> listingIds) {
        if (listingIds.isEmpty()) {
            return Map.of();
        }
        return dsl.select(LISTING_IMAGE.LISTING_ID, LISTING_IMAGE.IMAGE_KEY)
                .from(LISTING_IMAGE)
                .where(LISTING_IMAGE.LISTING_ID.in(listingIds))
                .and(LISTING_IMAGE.IS_PRIMARY.isTrue())
                .fetchMap(LISTING_IMAGE.LISTING_ID, LISTING_IMAGE.IMAGE_KEY);
    }

    /**
     * Whether a photo already sits on another seller's listing, deleted listings
     * included. A public listing page hands out a signed URL with the key readable
     * in it, so without this anyone could put another seller's photo on their own.
     */
    public boolean keyUsedByAnotherSeller(String imageKey, long sellerProfileId) {
        return dsl.fetchExists(dsl.selectOne()
                .from(LISTING_IMAGE)
                .join(LISTING).on(LISTING.ID.eq(LISTING_IMAGE.LISTING_ID))
                .where(LISTING_IMAGE.IMAGE_KEY.eq(imageKey))
                .and(LISTING.SELLER_PROFILE_ID.ne(sellerProfileId)));
    }

    /** Replaces the whole set in the order given; the first key is the primary photo. */
    public void replace(long listingId, List<String> imageKeys) {
        dsl.deleteFrom(LISTING_IMAGE)
                .where(LISTING_IMAGE.LISTING_ID.eq(listingId))
                .execute();

        if (imageKeys.isEmpty()) {
            return;
        }
        InsertValuesStep4<ListingImageRecord, Long, String, Short, Boolean> insert = dsl.insertInto(LISTING_IMAGE,
                LISTING_IMAGE.LISTING_ID, LISTING_IMAGE.IMAGE_KEY, LISTING_IMAGE.SORT_ORDER, LISTING_IMAGE.IS_PRIMARY);

        for (int i = 0; i < imageKeys.size(); i++) {
            insert = insert.values(listingId, imageKeys.get(i), (short) i, i == 0);
        }
        insert.execute();
    }

    private static ListingImage toImage(ListingImageRecord r) {
        return new ListingImage(
                r.getId(),
                r.getListingId(),
                r.getImageKey(),
                r.getSortOrder(),
                r.getIsPrimary(),
                r.getCreatedAt());
    }
}
