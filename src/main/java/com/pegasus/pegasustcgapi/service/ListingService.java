package com.pegasus.pegasustcgapi.service;

import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.common.Paging;
import com.pegasus.pegasustcgapi.dto.ListingCard;
import com.pegasus.pegasustcgapi.dto.ListingPhotoResponse;
import com.pegasus.pegasustcgapi.dto.PriceChangeResponse;
import com.pegasus.pegasustcgapi.dto.SellerListingResponse;
import com.pegasus.pegasustcgapi.dto.SellerListingSummaryResponse;
import com.pegasus.pegasustcgapi.dto.SimilarListingResponse;
import com.pegasus.pegasustcgapi.exception.ApiException;
import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.model.CardCondition;
import com.pegasus.pegasustcgapi.model.CatalogVariant;
import com.pegasus.pegasustcgapi.model.Listing;
import com.pegasus.pegasustcgapi.model.ListingImage;
import com.pegasus.pegasustcgapi.model.ListingStatus;
import com.pegasus.pegasustcgapi.model.ListingUnit;
import com.pegasus.pegasustcgapi.model.MarketKey;
import com.pegasus.pegasustcgapi.model.PricingMode;
import com.pegasus.pegasustcgapi.model.SellerProfile;
import com.pegasus.pegasustcgapi.port.SellerPort;
import com.pegasus.pegasustcgapi.repository.CatalogVariantRepository;
import com.pegasus.pegasustcgapi.repository.ListingImageRepository;
import com.pegasus.pegasustcgapi.repository.ListingPriceHistoryRepository;
import com.pegasus.pegasustcgapi.repository.ListingRepository;
import com.pegasus.pegasustcgapi.repository.ListingRepository.Details;
import com.pegasus.pegasustcgapi.repository.ListingRepository.Pricing;
import com.pegasus.pegasustcgapi.repository.ListingRepository.SellerListingQuery;
import com.pegasus.pegasustcgapi.repository.ListingUnitRepository;
import com.pegasus.pegasustcgapi.storage.StorageService;
import com.pegasus.pegasustcgapi.storage.UploadPurpose;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A seller's own listings: creating them, describing them, pricing them and
 * moving them through their states [RQ-5, RQ-6, RQ-9].
 *
 * <p>Everything is scoped to the caller's seller profile, so another seller's
 * listing id reads as not found. Reading needs a profile; changing anything needs
 * it to be VERIFIED [RQ-1].
 *
 * <p>One seller may list the same card in the same condition more than once — at
 * two prices, or as two lots. Nothing refuses that; every single-listing answer
 * carries the others as {@code similarListings}, and a write whose twin is priced
 * alike asks whether to merge.
 */
@Service
public class ListingService {

    private static final Set<ListingStatus> PAUSABLE = EnumSet.of(ListingStatus.ACTIVE, ListingStatus.SOLD_OUT);
    private static final Set<ListingStatus> PUBLISHABLE = EnumSet.of(ListingStatus.DRAFT, ListingStatus.PAUSED);
    private static final Set<ListingStatus> DELISTABLE = EnumSet.of(
            ListingStatus.DRAFT, ListingStatus.ACTIVE, ListingStatus.PAUSED, ListingStatus.SOLD_OUT);

    private final SellerPort sellers;
    private final ListingRepository listings;
    private final ListingUnitRepository units;
    private final ListingImageRepository images;
    private final ListingPriceHistoryRepository priceHistory;
    private final CatalogVariantRepository variants;
    private final ListingCardRenderer cards;
    private final StorageService storage;
    private final Clock clock;

    public ListingService(
            SellerPort sellers,
            ListingRepository listings,
            ListingUnitRepository units,
            ListingImageRepository images,
            ListingPriceHistoryRepository priceHistory,
            CatalogVariantRepository variants,
            ListingCardRenderer cards,
            StorageService storage,
            Clock clock) {

        this.sellers = sellers;
        this.listings = listings;
        this.units = units;
        this.images = images;
        this.priceHistory = priceHistory;
        this.variants = variants;
        this.cards = cards;
        this.storage = storage;
        this.clock = clock;
    }

    // ---------- reading ----------

    /** The dashboard, most recently changed first. Deleted listings are gone from it. */
    public PageResponse<SellerListingSummaryResponse> mine(
            long userId, ListingStatus status, Long variantId, CardCondition condition, int page, int size) {

        SellerProfile seller = sellers.requireProfile(userId);
        Paging paging = Paging.of(page, size);
        SellerListingQuery query = new SellerListingQuery(
                seller.id(), status, variantId, condition, paging.size(), paging.offset());

        List<Listing> rows = listings.findSellerPage(query);
        Map<Long, ListingCard> cardsByVariant = cards.cards(rows.stream().map(Listing::catalogVariantId).toList());
        Map<Long, String> photoByListing = images.primaryKeysOf(rows.stream().map(Listing::id).toList());

        List<SellerListingSummaryResponse> items = rows.stream()
                .map(l -> SellerListingSummaryResponse.of(l, cardsByVariant.get(l.catalogVariantId()),
                        cards.signed(photoByListing.get(l.id()))))
                .toList();

        return PageResponse.of(items, paging.page(), paging.size(), listings.countSeller(query));
    }

    public SellerListingResponse get(long userId, long listingId) {
        SellerProfile seller = sellers.requireProfile(userId);
        return render(require(seller, listingId));
    }

    /** Newest first. Each row says whether a person or the nightly job made the change. */
    public PageResponse<PriceChangeResponse> priceHistory(long userId, long listingId, int page, int size) {
        SellerProfile seller = sellers.requireProfile(userId);
        Listing listing = require(seller, listingId);
        Paging paging = Paging.of(page, size);

        List<PriceChangeResponse> rows = priceHistory.findPage(listing.id(), paging.size(), paging.offset())
                .stream().map(PriceChangeResponse::of).toList();

        return PageResponse.of(rows, paging.page(), paging.size(), priceHistory.count(listing.id()));
    }

    // ---------- writing ----------

    /** A DRAFT with no cards. Its opening price is the first row of its price history. */
    @Transactional
    public SellerListingResponse create(long userId, MarketKey market, Details details, Pricing pricing,
            List<String> photos) {

        SellerProfile seller = sellers.requireVerifiedSeller(userId);
        requireActiveVariant(market.catalogVariantId());
        requireGradingConsistent(details);
        requirePricingConsistent(pricing);
        requirePhotosUsable(seller.id(), photos, Set.of());

        long id = listings.insert(seller.id(), market, details, pricing);
        priceHistory.insert(id, null, pricing.price(), pricing.mode(), userId, "listing created");
        images.replace(id, photos);

        return render(require(seller, id));
    }

    /** Photos already on the listing are kept without asking storage about them again. */
    @Transactional
    public SellerListingResponse updateDetails(long userId, long listingId, Details details, List<String> photos) {
        SellerProfile seller = sellers.requireVerifiedSeller(userId);
        Listing listing = require(seller, listingId);
        requireGradingConsistent(details);

        Set<String> current = images.findByListing(listing.id()).stream()
                .map(ListingImage::imageKey)
                .collect(Collectors.toSet());
        requirePhotosUsable(seller.id(), photos, current);

        listings.updateDetails(listing.id(), details);
        images.replace(listing.id(), photos);
        return render(require(seller, listing.id()));
    }

    /**
     * One price for every card on the listing, changed in one row — no card can be
     * left behind at the old price. A change of amount is written to the history
     * with the seller's id; switching mode alone is not a price change.
     */
    @Transactional
    public SellerListingResponse changePrice(long userId, long listingId, PriceChange change) {
        SellerProfile seller = sellers.requireVerifiedSeller(userId);
        Listing listing = listings.lockOfSeller(listingId, seller.id())
                .orElseThrow(() -> new NotFoundException(ErrorCode.LISTING_NOT_FOUND));

        if (!listing.status().open()) {
            throw new ConflictException(ErrorCode.LISTING_CLOSED,
                    "A " + listing.status() + " listing keeps the price it closed at");
        }
        if (change.mode() == PricingMode.MANUAL && change.price() == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "price is required when pricingMode is MANUAL");
        }

        BigDecimal price = change.price() != null ? change.price() : listing.price();
        Pricing pricing = new Pricing(change.mode(), price, change.offsetPercent(), change.floor(), change.ceiling());
        requirePricingConsistent(pricing);

        listings.updatePricing(listing.id(), pricing);
        if (price.compareTo(listing.price()) != 0) {
            priceHistory.insert(listing.id(), listing.price(), price, change.mode(), userId, "set by seller");
        }
        return render(require(seller, listing.id()));
    }

    /**
     * Publish, pause, resume or close.
     *
     * <ul>
     *   <li>ACTIVE from DRAFT needs at least one card on the listing; from PAUSED
     *       with nothing left it lands in SOLD_OUT instead.</li>
     *   <li>DELISTED is for good. Cards on sale go back into the seller's hands;
     *       cards held by an order stay held and come back to hand when released.</li>
     * </ul>
     */
    @Transactional
    public SellerListingResponse changeStatus(long userId, long listingId, ListingStatus target) {
        SellerProfile seller = sellers.requireVerifiedSeller(userId);
        Listing listing = require(seller, listingId);

        if (listing.status() == target) {
            return render(listing);
        }
        if (!listing.status().sellerTargets().contains(target)) {
            throw transitionRefused(listing.status(), target);
        }

        boolean moved = switch (target) {
            case ACTIVE -> publish(listing);
            case PAUSED -> listings.changeStatus(listing.id(), ListingStatus.PAUSED, PAUSABLE);
            case DELISTED -> delist(listing);
            default -> throw transitionRefused(listing.status(), target);
        };
        if (!moved) {
            throw new ConflictException(ErrorCode.LISTING_STATUS_TRANSITION,
                    "The listing changed while this request was on its way; reload it and try again");
        }
        return render(require(seller, listing.id()));
    }

    /**
     * Gone from the dashboard and the market. The row stays, since order lines and
     * the stock ledger point at it; cards on sale go back into the seller's hands.
     */
    @Transactional
    public void delete(long userId, long listingId) {
        SellerProfile seller = sellers.requireVerifiedSeller(userId);
        Listing listing = require(seller, listingId);

        if (listing.status() == ListingStatus.BLOCKED) {
            throw new ConflictException(ErrorCode.LISTING_CLOSED, "A listing blocked by an admin cannot be deleted");
        }
        if (listing.quantityReserved() > 0) {
            throw new ConflictException(ErrorCode.LISTING_HAS_RESERVATIONS);
        }

        units.detach(units.lockListedOf(listing.id()).stream().map(ListingUnit::id).toList());
        if (!listings.softDelete(listing.id(), OffsetDateTime.now(clock))) {
            throw new ConflictException(ErrorCode.LISTING_HAS_RESERVATIONS);
        }
    }

    // ---------- moves ----------

    private boolean publish(Listing listing) {
        requireActiveVariant(listing.catalogVariantId());
        if (listing.status() == ListingStatus.DRAFT && listing.quantityAvailable() == 0) {
            throw new ConflictException(ErrorCode.LISTING_HAS_NO_STOCK);
        }
        return listings.activate(listing.id(), PUBLISHABLE, OffsetDateTime.now(clock));
    }

    /** The cards first, then the listing: the lock order this whole module keeps. */
    private boolean delist(Listing listing) {
        units.detach(units.lockListedOf(listing.id()).stream().map(ListingUnit::id).toList());
        return listings.changeStatus(listing.id(), ListingStatus.DELISTED, DELISTABLE);
    }

    private static ConflictException transitionRefused(ListingStatus from, ListingStatus to) {
        String why;
        if (from == ListingStatus.SOLD_OUT && to == ListingStatus.ACTIVE) {
            why = "A SOLD_OUT listing goes back on sale by itself when a card is put on it";
        } else if (from == ListingStatus.BLOCKED) {
            why = "This listing was blocked by an admin";
        } else if (from == ListingStatus.DELISTED) {
            why = "A DELISTED listing is closed for good; create a new one";
        } else {
            why = "A " + from + " listing cannot be moved to " + to + " by its seller";
        }
        return new ConflictException(ErrorCode.LISTING_STATUS_TRANSITION, why);
    }

    // ---------- rules ----------

    private Listing require(SellerProfile seller, long listingId) {
        return listings.findOfSeller(listingId, seller.id())
                .orElseThrow(() -> new NotFoundException(ErrorCode.LISTING_NOT_FOUND));
    }

    private void requireActiveVariant(long variantId) {
        CatalogVariant variant = variants.findById(variantId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.VARIANT_NOT_FOUND));
        if (!variant.active()) {
            throw new ConflictException(ErrorCode.VARIANT_INACTIVE);
        }
    }

    /** The table's CHECK says the same thing; saying it here makes it a 400 instead of a 500. */
    private static void requireGradingConsistent(Details details) {
        if (details.gradeValue() != null && details.gradingCompany() == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "gradeValue needs the gradingCompany that gave it");
        }
    }

    private static void requirePricingConsistent(Pricing pricing) {
        if (pricing.floor() != null && pricing.ceiling() != null
                && pricing.floor().compareTo(pricing.ceiling()) > 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "autoPriceFloor cannot be above autoPriceCeiling");
        }
    }

    /**
     * A new photo has to come from a listing upload, actually be there, and not
     * already sit on another seller's listing — a public listing page hands out a
     * signed URL with the key readable in it.
     */
    private void requirePhotosUsable(long sellerProfileId, List<String> photos, Set<String> alreadyOnListing) {
        if (new HashSet<>(photos).size() != photos.size()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "imageKeys lists the same photo twice");
        }
        String prefix = UploadPurpose.LISTING_IMAGE.prefix() + "/";
        for (String key : photos) {
            if (alreadyOnListing.contains(key)) {
                continue;
            }
            if (!key.startsWith(prefix)) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED,
                        "imageKeys must come from a LISTING_IMAGE upload (" + prefix + "...)");
            }
            storage.requireUploaded(key);
            if (images.keyUsedByAnotherSeller(key, sellerProfileId)) {
                throw new ConflictException(ErrorCode.LISTING_PHOTO_IN_USE);
            }
        }
    }

    // ---------- rendering ----------

    private SellerListingResponse render(Listing listing) {
        List<ListingPhotoResponse> photos = images.findByListing(listing.id()).stream()
                .map(image -> ListingPhotoResponse.of(image, cards.signed(image.imageKey())))
                .toList();

        MarketKey market = new MarketKey(listing.catalogVariantId(), listing.condition());
        List<SimilarListingResponse> similar = listings.findSimilar(listing.sellerProfileId(), market, listing.id())
                .stream()
                .map(other -> SimilarListingResponse.of(other, listing))
                .toList();

        return SellerListingResponse.of(listing, cards.card(listing.catalogVariantId()), photos, similar);
    }

    /**
     * @param price required for MANUAL; for AUTO_MEDIAN null keeps the current price
     */
    public record PriceChange(
            PricingMode mode,
            BigDecimal price,
            BigDecimal offsetPercent,
            BigDecimal floor,
            BigDecimal ceiling) {
    }
}
