package com.pegasus.pegasustcgapi.service.sale;

import com.pegasus.pegasustcgapi.model.catalog.CardCondition;
import com.pegasus.pegasustcgapi.model.listing.ListingStatus;
import com.pegasus.pegasustcgapi.model.sale.PriceSale;
import com.pegasus.pegasustcgapi.model.sale.PriceSale.OwnerType;
import com.pegasus.pegasustcgapi.model.sale.SaleStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * Which sale price a listing has, if any [TCG-676]. Pure: the caller loads the
 * listing, the sales and their price_sale_listing rows, and gets back the one
 * price that wins.
 *
 * <ul>
 *   <li>Only an ACTIVE listing takes a sale price. Auctions never do: a card on
 *       auction belongs to no listing.</li>
 *   <li>A discount never takes a card below ฿1, and one that comes to nothing
 *       (a fixed price at or above the asking price) is no sale at all.</li>
 *   <li>A flash sale whose quota for the listing is used up no longer counts.</li>
 *   <li>Of several, the lowest price wins; on a tie Pegasus's campaign wins, so
 *       the shop keeps its money; after that the older sale.</li>
 * </ul>
 */
public final class SalePriceResolver {

    static final BigDecimal MIN_PRICE = BigDecimal.ONE.setScale(2);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private static final Comparator<Resolved> BEST = Comparator
            .comparing(Resolved::salePrice)
            .thenComparing(r -> r.fundedBy() == OwnerType.PLATFORM ? 0 : 1)
            .thenComparingLong(Resolved::saleId);

    private SalePriceResolver() {
    }

    /** What a listing is, as far as deciding its sale price goes. */
    public record ListingFacts(
            long listingId,
            long sellerProfileId,
            long gameId,
            long categoryId,
            Long cardSetId,
            CardCondition condition,
            BigDecimal price,
            ListingStatus status) {
    }

    /**
     * A row of price_sale_listing.
     *
     * @param salePrice the listing's own price under a FIXED_PRICE sale; null otherwise
     * @param quota     overrides the sale's per-listing quota; null means use the sale's
     */
    public record SaleListing(BigDecimal salePrice, Integer quota, int soldCount) {
    }

    /**
     * The price a listing sells at under one sale.
     *
     * @param discountAmount per card, {@code listing.price - salePrice}
     * @param endsAt         when the sale stops
     * @param quotaLeft      cards left at this price; null when the sale has no quota
     */
    public record Resolved(
            long listingId,
            long saleId,
            BigDecimal salePrice,
            BigDecimal discountAmount,
            OwnerType fundedBy,
            OffsetDateTime endsAt,
            Integer quotaLeft) {
    }

    /**
     * The winning sale price for a listing, or empty when it sells at its own price.
     *
     * @param sales    candidate sales; ones not running at {@code now} are skipped
     * @param rowsOf   the price_sale_listing row for (sale id, listing id), or null
     */
    public static Optional<Resolved> resolve(ListingFacts listing, Collection<PriceSale> sales,
            BiFunction<Long, Long, SaleListing> rowsOf, OffsetDateTime now) {

        if (listing.status() != ListingStatus.ACTIVE) {
            return Optional.empty();
        }
        return sales.stream()
                .filter(s -> s.statusAt(now, false) == SaleStatus.ACTIVE)
                .map(s -> price(s, listing, rowsOf.apply(s.id(), listing.listingId())))
                .flatMap(Optional::stream)
                .min(BEST);
    }

    /**
     * Whether a sale reaches this listing at all — shop, scope, condition and
     * minimum price — whatever its dates, pause or quota. This is what locks a
     * listing's price while a sale is scheduled or running.
     *
     * @param row the listing's price_sale_listing row for this sale, or null
     */
    public static boolean covers(PriceSale sale, ListingFacts listing, SaleListing row) {
        if (sale.ownerType() == OwnerType.SELLER
                && (sale.sellerProfileId() == null || sale.sellerProfileId() != listing.sellerProfileId())) {
            return false;
        }
        boolean inScope = switch (sale.scopeType()) {
            case ALL, SHOP -> true;
            case GAME -> sale.scopeIds().contains(listing.gameId());
            case CATEGORY -> sale.scopeIds().contains(listing.categoryId());
            case CARD_SET -> listing.cardSetId() != null && sale.scopeIds().contains(listing.cardSetId());
            case LISTINGS -> row != null;
        };
        return inScope
                && (sale.conditions().isEmpty() || sale.conditions().contains(listing.condition()))
                && (sale.minUnitPrice() == null || listing.price().compareTo(sale.minUnitPrice()) >= 0);
    }

    /**
     * The price under this one sale, ignoring its dates and pause, or empty when
     * the sale does not reach the listing, has no quota left for it, or would not
     * lower its price.
     */
    public static Optional<Resolved> price(PriceSale sale, ListingFacts listing, SaleListing row) {
        if (!covers(sale, listing, row)) {
            return Optional.empty();
        }

        Integer quota = row != null && row.quota() != null ? row.quota() : sale.perListingQuota();
        Integer quotaLeft = null;
        if (quota != null) {
            quotaLeft = quota - (row != null ? row.soldCount() : 0);
            if (quotaLeft <= 0) {
                return Optional.empty();
            }
        }

        BigDecimal price = listing.price();
        BigDecimal discount = switch (sale.discountType()) {
            case PERCENT -> {
                BigDecimal d = price.multiply(sale.discountValue()).divide(HUNDRED, 2, RoundingMode.HALF_UP);
                yield sale.maxDiscountPerUnit() != null ? d.min(sale.maxDiscountPerUnit()) : d;
            }
            case FIXED_OFF -> sale.discountValue();
            case FIXED_PRICE -> row != null && row.salePrice() != null
                    ? price.subtract(row.salePrice())
                    : BigDecimal.ZERO;
        };
        discount = discount.min(price.subtract(MIN_PRICE)).setScale(2, RoundingMode.HALF_UP);
        if (discount.signum() <= 0) {
            return Optional.empty();
        }

        return Optional.of(new Resolved(listing.listingId(), sale.id(),
                price.subtract(discount).setScale(2, RoundingMode.HALF_UP), discount,
                sale.ownerType(), sale.effectiveEnd(), quotaLeft));
    }
}
