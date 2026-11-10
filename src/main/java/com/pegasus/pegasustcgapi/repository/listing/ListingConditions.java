package com.pegasus.pegasustcgapi.repository.listing;

import static com.pegasus.pegasustcgapi.jooq.tables.Listing.LISTING;
import static com.pegasus.pegasustcgapi.jooq.tables.ListingSalePrice.LISTING_SALE_PRICE;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerProfile.SELLER_PROFILE;
import static com.pegasus.pegasustcgapi.jooq.tables.UserAccount.USER_ACCOUNT;

import com.pegasus.pegasustcgapi.model.listing.ListingStatus;
import com.pegasus.pegasustcgapi.model.seller.SellerStatus;
import com.pegasus.pegasustcgapi.model.user.UserStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.impl.DSL;

/**
 * jOOQ conditions and fields every listing query shares: which shops are open, which listings
 * can be bought now, and the price a buyer pays while a sale runs.
 */
public final class ListingConditions {

    private ListingConditions() {
    }

    /**
     * A seller buyers can see: verified, not on vacation, and an account that is
     * still active. Vacation hides every listing at once without touching their
     * statuses. {@link com.pegasus.pegasustcgapi.repository.pricing.ProductMarketRepository}
     * prices the catalogue by the same rule.
     */
    public static Condition sellerProfileOpenForBusiness() {
        return SELLER_PROFILE.STATUS.eq(SellerStatus.VERIFIED.name())
                .and(SELLER_PROFILE.VACATION_MODE.isFalse())
                .and(USER_ACCOUNT.STATUS.eq(UserStatus.ACTIVE))
                .and(USER_ACCOUNT.DELETED_AT.isNull());
    }

    /** The SQL side of {@link ListingRepository.Offer#purchasable()}, for counting what could be bought now. */
    public static Condition purchasableNow() {
        return LISTING.STATUS.eq(ListingStatus.ACTIVE.name())
                .and(LISTING.QUANTITY_AVAILABLE.gt(0))
                .and(sellerOpenForBusiness());
    }

    public static Condition sellerOpenForBusiness() {
        return LISTING.DELETED_AT.isNull()
                .and(sellerProfileOpenForBusiness());
    }

    /**
     * The listing's sale row while its sale still runs [TCG-679]. A row past its end
     * waits for the next refresh to be removed; until then it is not a sale price.
     */
    public static Condition salePriceRunning(OffsetDateTime now) {
        return LISTING_SALE_PRICE.LISTING_ID.eq(LISTING.ID)
                .and(LISTING_SALE_PRICE.ENDS_AT.gt(now));
    }

    /**
     * What a card sells for now: the sale price while one runs, else the asking price.
     * Read it with {@link #salePriceRunning} left-joined, as every buyer page does.
     */
    public static final Field<BigDecimal> EFFECTIVE_PRICE = DSL.coalesce(LISTING_SALE_PRICE.SALE_PRICE, LISTING.PRICE);

    /** "%text%" with the LIKE wildcards in the text taken literally. */
    static String containsPattern(String text) {
        return "%" + text.trim()
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_") + "%";
    }

    /**
     * A real {@code ILIKE}, as in {@link com.pegasus.pegasustcgapi.repository.catalog.CatalogProductRepository}:
     * the trigram indexes are on the columns themselves, and {@code lower()} around
     * them would leave the search on a sequential scan.
     */
    static Condition ilike(Field<String> column, String pattern) {
        return DSL.condition("{0} ILIKE {1} ESCAPE '\\'", column, DSL.val(pattern));
    }
}
