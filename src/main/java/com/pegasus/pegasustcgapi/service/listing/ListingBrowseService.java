package com.pegasus.pegasustcgapi.service.listing;

import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.common.Paging;
import com.pegasus.pegasustcgapi.dto.listing.ListingCard;
import com.pegasus.pegasustcgapi.dto.pricing.MarketStatResponse;
import com.pegasus.pegasustcgapi.dto.listing.PublicListingDetailResponse;
import com.pegasus.pegasustcgapi.dto.listing.PublicListingResponse;
import com.pegasus.pegasustcgapi.dto.sale.SalePricing;
import com.pegasus.pegasustcgapi.dto.user.PublicProfileResponse;
import com.pegasus.pegasustcgapi.exception.ApiException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.model.auth.AuthUser;
import com.pegasus.pegasustcgapi.model.catalog.CardCondition;
import com.pegasus.pegasustcgapi.model.listing.Listing;
import com.pegasus.pegasustcgapi.model.listing.ListingImage;
import com.pegasus.pegasustcgapi.model.pricing.MarketKey;
import com.pegasus.pegasustcgapi.model.user.UserStatus;
import com.pegasus.pegasustcgapi.repository.listing.ListingImageRepository;
import com.pegasus.pegasustcgapi.repository.listing.ListingRepository;
import com.pegasus.pegasustcgapi.repository.listing.ListingRepository.PublicListing;
import com.pegasus.pegasustcgapi.repository.listing.ListingRepository.PublicListingQuery;
import com.pegasus.pegasustcgapi.repository.user.UserRepository;
import com.pegasus.pegasustcgapi.repository.pricing.VariantMarketStatRepository;
import com.pegasus.pegasustcgapi.service.sale.SalePricingService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * The market as anyone sees it, signed in or not: every listing on sale, one
 * listing's page, and a seller's storefront — which is just their profile [RQ-2].
 *
 * <p>Only listings from sellers open for business appear: verified, not on
 * vacation, with an active account.
 */
@Service
public class ListingBrowseService {

    private final ListingRepository listings;
    private final ListingImageRepository images;
    private final VariantMarketStatRepository stats;
    private final UserRepository users;
    private final ListingCardRenderer cards;
    private final SalePricingService salePricing;
    private final Clock clock;

    public ListingBrowseService(
            ListingRepository listings,
            ListingImageRepository images,
            VariantMarketStatRepository stats,
            UserRepository users,
            ListingCardRenderer cards,
            SalePricingService salePricing,
            Clock clock) {

        this.listings = listings;
        this.images = images;
        this.stats = stats;
        this.users = users;
        this.cards = cards;
        this.salePricing = salePricing;
        this.clock = clock;
    }

    /**
     * What the market, search and a sale's own page filter on beyond the card [TCG-679].
     *
     * @param onSale      true: only cards on sale; false: only cards off sale; null: both
     * @param saleId      only cards priced by this sale
     * @param minDiscount only cards at least this many percent off, 1–99
     */
    public record SaleFilter(Boolean onSale, Long saleId, BigDecimal minDiscount) {
        public static final SaleFilter NONE = new SaleFilter(null, null, null);
    }

    /** ACTIVE listings only, cheapest first unless asked otherwise. */
    public PageResponse<PublicListingResponse> market(Long variantId, Long productId, Short gameId,
            CardCondition condition, BigDecimal minPrice, BigDecimal maxPrice, String sort, int page, int size) {
        return market(variantId, productId, gameId, condition, minPrice, maxPrice, SaleFilter.NONE, sort, page, size);
    }

    /** ACTIVE listings only, cheapest first unless asked otherwise; prices are what a buyer pays now. */
    public PageResponse<PublicListingResponse> market(Long variantId, Long productId, Short gameId,
            CardCondition condition, BigDecimal minPrice, BigDecimal maxPrice, SaleFilter sale, String sort,
            int page, int size) {

        if (minPrice != null && maxPrice != null && minPrice.compareTo(maxPrice) > 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "minPrice cannot be above maxPrice");
        }
        SaleFilter f = sale == null ? SaleFilter.NONE : sale;
        if (f.minDiscount() != null
                && (f.minDiscount().compareTo(BigDecimal.ONE) < 0 || f.minDiscount().compareTo(BigDecimal.valueOf(99)) > 0)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "minDiscount must be between 1 and 99");
        }
        Paging paging = Paging.of(page, size);
        return page(new PublicListingQuery(null, variantId, productId, gameId, condition, minPrice, maxPrice,
                f.onSale(), f.saleId(), f.minDiscount(), parseSort(sort), paging.size(), paging.offset(),
                OffsetDateTime.now(clock)), paging);
    }

    /**
     * What a profile has on sale. An account that has never sold has an empty
     * shelf; a missing or suspended one is not found, and reads the same either way.
     */
    public PageResponse<PublicListingResponse> storefront(String username, Long variantId, CardCondition condition,
            String sort, int page, int size) {

        AuthUser owner = users.findByUsername(username.trim().toLowerCase(Locale.ROOT))
                .filter(user -> user.status() == UserStatus.ACTIVE)
                .orElseThrow(() -> new NotFoundException(ErrorCode.USER_NOT_FOUND));

        Paging paging = Paging.of(page, size);
        return page(new PublicListingQuery(owner.id(), variantId, null, null, condition, null, null,
                null, null, null, parseSort(sort), paging.size(), paging.offset(), OffsetDateTime.now(clock)), paging);
    }

    /** Profile details are available even before the owner publishes a listing. */
    public PublicProfileResponse profile(String username) {
        AuthUser owner = users.findByUsername(username.trim().toLowerCase(Locale.ROOT))
                .filter(user -> user.status() == UserStatus.ACTIVE)
                .orElseThrow(() -> new NotFoundException(ErrorCode.USER_NOT_FOUND));
        return new PublicProfileResponse(owner.username(), owner.displayName(), cards.signed(owner.avatarUrl()),
                owner.phonePublic() ? owner.phone() : null);
    }

    /** A sold-out listing still has a page that says so; any other state is not found. */
    public PublicListingDetailResponse get(long listingId) {
        PublicListing row = listings.findPublic(listingId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.LISTING_NOT_FOUND));
        Listing listing = row.listing();

        List<String> photoUrls = images.findByListing(listing.id()).stream()
                .map(ListingImage::imageKey)
                .map(cards::signed)
                .toList();

        PublicListingResponse shown = PublicListingResponse.of(row, cards.card(listing.catalogVariantId()),
                photoUrls.isEmpty() ? null : photoUrls.getFirst(), cards.signed(row.sellerAvatarUrl()),
                salePricing.pricing(listing.id(), listing.price()));

        MarketStatResponse market = stats.latest(new MarketKey(listing.catalogVariantId(), listing.condition()))
                .map(MarketStatResponse::of)
                .orElse(null);

        return new PublicListingDetailResponse(shown, photoUrls, market);
    }

    /** The listings of a page, priced, cards and photos in one read each. Shared with the sale pages. */
    public List<PublicListingResponse> render(List<PublicListing> rows) {
        Map<Long, ListingCard> cardsByVariant = cards.cards(
                rows.stream().map(r -> r.listing().catalogVariantId()).toList());
        Map<Long, String> photoByListing = images.primaryKeysOf(
                rows.stream().map(r -> r.listing().id()).toList());
        Map<Long, BigDecimal> listPrices = new LinkedHashMap<>();
        rows.forEach(r -> listPrices.put(r.listing().id(), r.listing().price()));
        Map<Long, SalePricing> pricing = salePricing.pricing(listPrices);

        return rows.stream()
                .map(r -> PublicListingResponse.of(r, cardsByVariant.get(r.listing().catalogVariantId()),
                        cards.signed(photoByListing.get(r.listing().id())), cards.signed(r.sellerAvatarUrl()),
                        pricing.get(r.listing().id())))
                .toList();
    }

    /** One page of the market under {@code query}. */
    public PageResponse<PublicListingResponse> page(PublicListingQuery query, Paging paging) {
        return PageResponse.of(render(listings.findPublicPage(query)), paging.page(), paging.size(),
                listings.countPublic(query));
    }

    static PublicListingQuery.Sort parseSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return PublicListingQuery.Sort.PRICE_ASC;
        }
        return switch (sort.trim().toLowerCase(Locale.ROOT)) {
            case "price", "price_asc" -> PublicListingQuery.Sort.PRICE_ASC;
            case "price_desc" -> PublicListingQuery.Sort.PRICE_DESC;
            case "newest" -> PublicListingQuery.Sort.NEWEST;
            case "discount_desc" -> PublicListingQuery.Sort.DISCOUNT_DESC;
            case "ending_soon" -> PublicListingQuery.Sort.ENDING_SOON;
            default -> throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "sort must be one of price, price_desc, newest, discount_desc, ending_soon");
        };
    }
}
