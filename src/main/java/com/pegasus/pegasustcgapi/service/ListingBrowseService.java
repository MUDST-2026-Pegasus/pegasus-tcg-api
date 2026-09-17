package com.pegasus.pegasustcgapi.service;

import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.common.Paging;
import com.pegasus.pegasustcgapi.dto.ListingCard;
import com.pegasus.pegasustcgapi.dto.MarketStatResponse;
import com.pegasus.pegasustcgapi.dto.PublicListingDetailResponse;
import com.pegasus.pegasustcgapi.dto.PublicListingResponse;
import com.pegasus.pegasustcgapi.exception.ApiException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.model.AuthUser;
import com.pegasus.pegasustcgapi.model.CardCondition;
import com.pegasus.pegasustcgapi.model.Listing;
import com.pegasus.pegasustcgapi.model.ListingImage;
import com.pegasus.pegasustcgapi.model.MarketKey;
import com.pegasus.pegasustcgapi.model.UserStatus;
import com.pegasus.pegasustcgapi.repository.ListingImageRepository;
import com.pegasus.pegasustcgapi.repository.ListingRepository;
import com.pegasus.pegasustcgapi.repository.ListingRepository.PublicListing;
import com.pegasus.pegasustcgapi.repository.ListingRepository.PublicListingQuery;
import com.pegasus.pegasustcgapi.repository.UserRepository;
import com.pegasus.pegasustcgapi.repository.VariantMarketStatRepository;
import java.math.BigDecimal;
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

    public ListingBrowseService(
            ListingRepository listings,
            ListingImageRepository images,
            VariantMarketStatRepository stats,
            UserRepository users,
            ListingCardRenderer cards) {

        this.listings = listings;
        this.images = images;
        this.stats = stats;
        this.users = users;
        this.cards = cards;
    }

    /** ACTIVE listings only, cheapest first unless asked otherwise. */
    public PageResponse<PublicListingResponse> market(Long variantId, Long productId, Short gameId,
            CardCondition condition, BigDecimal minPrice, BigDecimal maxPrice, String sort, int page, int size) {

        if (minPrice != null && maxPrice != null && minPrice.compareTo(maxPrice) > 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "minPrice cannot be above maxPrice");
        }
        Paging paging = Paging.of(page, size);
        return page(new PublicListingQuery(null, variantId, productId, gameId, condition, minPrice, maxPrice,
                parseSort(sort), paging.size(), paging.offset()), paging);
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
                parseSort(sort), paging.size(), paging.offset()), paging);
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
                photoUrls.isEmpty() ? null : photoUrls.getFirst());

        MarketStatResponse market = stats.latest(new MarketKey(listing.catalogVariantId(), listing.condition()))
                .map(MarketStatResponse::of)
                .orElse(null);

        return new PublicListingDetailResponse(shown, photoUrls, market);
    }

    private PageResponse<PublicListingResponse> page(PublicListingQuery query, Paging paging) {
        List<PublicListing> rows = listings.findPublicPage(query);

        Map<Long, ListingCard> cardsByVariant = cards.cards(
                rows.stream().map(r -> r.listing().catalogVariantId()).toList());
        Map<Long, String> photoByListing = images.primaryKeysOf(
                rows.stream().map(r -> r.listing().id()).toList());

        List<PublicListingResponse> items = rows.stream()
                .map(r -> PublicListingResponse.of(r, cardsByVariant.get(r.listing().catalogVariantId()),
                        cards.signed(photoByListing.get(r.listing().id()))))
                .toList();

        return PageResponse.of(items, paging.page(), paging.size(), listings.countPublic(query));
    }

    static PublicListingQuery.Sort parseSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return PublicListingQuery.Sort.PRICE_ASC;
        }
        return switch (sort.trim().toLowerCase(Locale.ROOT)) {
            case "price", "price_asc" -> PublicListingQuery.Sort.PRICE_ASC;
            case "price_desc" -> PublicListingQuery.Sort.PRICE_DESC;
            case "newest" -> PublicListingQuery.Sort.NEWEST;
            default -> throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "sort must be one of price, price_desc, newest");
        };
    }
}
