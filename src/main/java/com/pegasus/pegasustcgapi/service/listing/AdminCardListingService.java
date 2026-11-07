package com.pegasus.pegasustcgapi.service.listing;

import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.common.Paging;
import com.pegasus.pegasustcgapi.dto.catalog.AdminProductSummaryResponse;
import com.pegasus.pegasustcgapi.dto.catalog.ProductSummaryResponse;
import com.pegasus.pegasustcgapi.dto.listing.AdminCardListingResponse;
import com.pegasus.pegasustcgapi.exception.BadRequestException;
import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.model.listing.Listing;
import com.pegasus.pegasustcgapi.model.listing.ListingStatus;
import com.pegasus.pegasustcgapi.model.seller.SellerStatus;
import com.pegasus.pegasustcgapi.repository.catalog.CatalogProductRepository;
import com.pegasus.pegasustcgapi.repository.listing.AdminCardListingRepository;
import com.pegasus.pegasustcgapi.repository.listing.AdminCardListingRepository.ProductStock;
import com.pegasus.pegasustcgapi.repository.listing.AdminCardListingRepository.Query;
import com.pegasus.pegasustcgapi.repository.listing.AdminCardListingRepository.Row;
import com.pegasus.pegasustcgapi.service.sale.SalePriceRefresher;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who sells a card and how much is left, and an admin's block on one listing
 * [TCG-730]. A block changes the listing and nothing else: cards held by open
 * orders stay held and those orders carry on.
 */
@Service
public class AdminCardListingService {

    private static final Logger AUDIT = LoggerFactory.getLogger("pegasus.audit.listing");

    private static final Set<ListingStatus> BLOCKABLE = EnumSet.of(
            ListingStatus.DRAFT, ListingStatus.ACTIVE, ListingStatus.PAUSED, ListingStatus.SOLD_OUT);

    private final AdminCardListingRepository listings;
    private final CatalogProductRepository products;
    private final ListingCardRenderer cards;
    private final SalePriceRefresher salePrices;
    private final Clock clock;

    public AdminCardListingService(AdminCardListingRepository listings, CatalogProductRepository products,
            ListingCardRenderer cards, SalePriceRefresher salePrices, Clock clock) {
        this.listings = listings;
        this.products = products;
        this.cards = cards;
        this.salePrices = salePrices;
        this.clock = clock;
    }

    /** The summary describes the whole card, so it does not move with the list's filters. */
    public AdminCardListingResponse.Page ofProduct(long productId, String search, Long variantId,
            ListingStatus status, int page, int size) {
        if (products.findById(productId).isEmpty()) {
            throw new NotFoundException(ErrorCode.PRODUCT_NOT_FOUND);
        }
        Paging paging = Paging.of(page, size);
        Query query = new Query(productId, search, variantId, status, paging.size(), paging.offset(),
                OffsetDateTime.now(clock));
        List<AdminCardListingResponse> items = listings.find(query).stream().map(this::toResponse).toList();
        return new AdminCardListingResponse.Page(
                PageResponse.of(items, paging.page(), paging.size(), listings.count(query)),
                listings.summary(productId));
    }

    public AdminCardListingResponse one(long listingId) {
        return listings.findOne(listingId, OffsetDateTime.now(clock))
                .map(this::toResponse)
                .orElseThrow(() -> new NotFoundException(ErrorCode.LISTING_NOT_FOUND));
    }

    /** Adds who sells each card on a page of browse results, with one read for the whole page. */
    public PageResponse<AdminProductSummaryResponse> withStock(PageResponse<ProductSummaryResponse> page) {
        Map<Long, ProductStock> stock = listings.stockByProduct(
                page.items().stream().map(ProductSummaryResponse::id).toList());
        List<AdminProductSummaryResponse> items = page.items().stream().map(product -> {
            ProductStock s = stock.getOrDefault(product.id(), ProductStock.NONE);
            return new AdminProductSummaryResponse(product, s.sellerCount(), s.availableQuantity(),
                    s.blockedListingCount());
        }).toList();
        return new PageResponse<>(items, page.page(), page.size(), page.totalItems(), page.totalPages(),
                page.correctedQuery());
    }

    @Transactional
    public void block(long listingId, long adminId, String reason) {
        String explanation = requiredReason(reason);
        Listing listing = requireLocked(listingId);
        if (!BLOCKABLE.contains(listing.status())) {
            throw new ConflictException(ErrorCode.LISTING_STATUS_TRANSITION,
                    "A " + listing.status() + " listing cannot be blocked");
        }
        listings.block(listingId, listing.status(), adminId, explanation, OffsetDateTime.now(clock));
        salePrices.refreshListings(List.of(listingId));
        AUDIT.info("listing.block id={} by={} from={} reason={}", listingId, adminId, listing.status(), explanation);
    }

    /**
     * Back to where it was. A listing that was on the market goes back only if its
     * shop may still sell: the database refuses ACTIVE otherwise, so it waits PAUSED.
     */
    @Transactional
    public void unblock(long listingId, long adminId, String reason) {
        String explanation = requiredReason(reason);
        Listing listing = requireLocked(listingId);
        if (listing.status() != ListingStatus.BLOCKED) {
            throw new ConflictException(ErrorCode.LISTING_STATUS_TRANSITION, "This listing is not blocked");
        }
        ListingStatus from = listing.block() == null ? ListingStatus.PAUSED : listing.block().fromStatus();
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (from == ListingStatus.ACTIVE || from == ListingStatus.SOLD_OUT) {
            SellerStatus shop = listings.findOne(listingId, now).orElseThrow().offer().sellerStatus();
            if (shop == SellerStatus.VERIFIED) {
                listings.unblockToMarket(listingId, now);
            } else {
                listings.unblockTo(listingId, ListingStatus.PAUSED);
            }
        } else {
            listings.unblockTo(listingId, from == null ? ListingStatus.PAUSED : from);
        }
        salePrices.refreshListings(List.of(listingId));
        AUDIT.info("listing.unblock id={} by={} reason={}", listingId, adminId, explanation);
    }

    private Listing requireLocked(long listingId) {
        return listings.lock(listingId).orElseThrow(() -> new NotFoundException(ErrorCode.LISTING_NOT_FOUND));
    }

    private static String requiredReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BadRequestException(ErrorCode.VALIDATION_FAILED, "Reason is required");
        }
        return reason.trim();
    }

    private AdminCardListingResponse toResponse(Row row) {
        Listing l = row.offer().listing();
        return new AdminCardListingResponse(
                l.id(), l.catalogVariantId(), l.condition(), l.gradingCompany(), l.gradeValue(), l.price(),
                row.salePrice(), row.saleEndsAt(), l.pricingMode(), l.quantityAvailable(), l.quantityReserved(),
                l.quantityTotal(), l.status(), l.lotLabel(), l.publicNote(), l.publishedAt(), l.updatedAt(),
                row.offer().purchasable(),
                new AdminCardListingResponse.Seller(
                        l.sellerProfileId(), row.sellerUserId(), row.offer().sellerUsername(),
                        row.offer().sellerDisplayName(), cards.signed(row.avatarKey()),
                        row.offer().sellerStatus(), row.offer().sellerOnVacation(), row.offer().sellerAccountActive(),
                        row.ratingAvg(), row.ratingCount()),
                l.block() == null ? null : new AdminCardListingResponse.Block(
                        l.block().reason(), row.blockedByUsername(), l.block().at(), l.block().fromStatus()));
    }
}
