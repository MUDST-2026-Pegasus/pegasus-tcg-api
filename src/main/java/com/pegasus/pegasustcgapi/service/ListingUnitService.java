package com.pegasus.pegasustcgapi.service;

import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.common.Paging;
import com.pegasus.pegasustcgapi.dto.ListingCard;
import com.pegasus.pegasustcgapi.dto.ListingUnitResponse;
import com.pegasus.pegasustcgapi.exception.ApiException;
import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.model.CardCondition;
import com.pegasus.pegasustcgapi.model.CatalogVariant;
import com.pegasus.pegasustcgapi.model.Listing;
import com.pegasus.pegasustcgapi.model.ListingUnit;
import com.pegasus.pegasustcgapi.model.ListingUnitStatus;
import com.pegasus.pegasustcgapi.model.MarketKey;
import com.pegasus.pegasustcgapi.model.SellerProfile;
import com.pegasus.pegasustcgapi.port.SellerPort;
import com.pegasus.pegasustcgapi.repository.CatalogVariantRepository;
import com.pegasus.pegasustcgapi.repository.ListingRepository;
import com.pegasus.pegasustcgapi.repository.ListingUnitRepository;
import com.pegasus.pegasustcgapi.repository.ListingUnitRepository.NewUnits;
import com.pegasus.pegasustcgapi.repository.ListingUnitRepository.UnitQuery;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A seller's physical cards [RQ-9]: bringing them in, putting them on listings,
 * moving them between listings, and writing them off.
 *
 * <p>A card keeps its UUID for life. Giving one card of a group its own price is
 * a move to another listing, never a delete and re-create, so its cost and
 * history stay attached.
 *
 * <p>Each write locks the card first and lets the trigger take the listing, the
 * same order checkout uses — see {@link ListingUnitRepository}.
 */
@Service
public class ListingUnitService {

    private final SellerPort sellers;
    private final ListingRepository listings;
    private final ListingUnitRepository units;
    private final CatalogVariantRepository variants;
    private final InventoryLedger ledger;
    private final ListingCardRenderer cards;
    private final Clock clock;

    public ListingUnitService(
            SellerPort sellers,
            ListingRepository listings,
            ListingUnitRepository units,
            CatalogVariantRepository variants,
            InventoryLedger ledger,
            ListingCardRenderer cards,
            Clock clock) {

        this.sellers = sellers;
        this.listings = listings;
        this.units = units;
        this.variants = variants;
        this.ledger = ledger;
        this.cards = cards;
        this.clock = clock;
    }

    // ---------- reading ----------

    /** All of the seller's cards; {@code status} IN_STOCK narrows to the ones in hand. */
    public PageResponse<ListingUnitResponse> mine(long userId, Long listingId, ListingUnitStatus status,
            Long variantId, CardCondition condition, int page, int size) {

        SellerProfile seller = sellers.requireProfile(userId);
        return page(new UnitQuery(seller.id(), listingId, status, variantId, condition, 0, 0), page, size);
    }

    public PageResponse<ListingUnitResponse> onListing(long userId, long listingId, ListingUnitStatus status,
            int page, int size) {

        SellerProfile seller = sellers.requireProfile(userId);
        requireListing(seller, listingId);
        return page(new UnitQuery(seller.id(), listingId, status, null, null, 0, 0), page, size);
    }

    public ListingUnitResponse get(long userId, UUID unitUid) {
        SellerProfile seller = sellers.requireProfile(userId);
        return render(requireUnit(seller, unitUid));
    }

    public ListingUnitResponse getOnListing(long userId, long listingId, UUID unitUid) {
        SellerProfile seller = sellers.requireProfile(userId);
        requireListing(seller, listingId);
        return render(requireOnListing(requireUnit(seller, unitUid), listingId));
    }

    // ---------- bringing cards in ----------

    /**
     * New cards, each with its own UUID and its own ledger line, booked into the
     * seller's average cost.
     *
     * <p>Onto a listing, the cards take its printing and condition; naming a
     * different one is a mismatch rather than silently ignored. A SOLD_OUT listing
     * is back on sale as soon as they land.
     */
    @Transactional
    public List<ListingUnitResponse> stockIn(long userId, StockIn stockIn) {
        SellerProfile seller = sellers.requireVerifiedSeller(userId);
        MarketKey market = marketFor(seller, stockIn);

        requireActiveVariant(market.catalogVariantId());
        if (stockIn.certNumber() != null && stockIn.quantity() != 1) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "A certified slab is one card: send quantity 1 with a certNumber");
        }

        OffsetDateTime acquiredAt = stockIn.acquiredAt() != null ? stockIn.acquiredAt() : OffsetDateTime.now(clock);
        List<ListingUnit> created = units.insert(new NewUnits(seller.id(), market.catalogVariantId(),
                market.condition(), stockIn.listingId(), stockIn.quantity(), stockIn.unitCost(), acquiredAt,
                stockIn.certNumber(), stockIn.unitNote()));

        ledger.stockIn(created, stockIn.unitCost(), userId);
        return render(created);
    }

    private MarketKey marketFor(SellerProfile seller, StockIn stockIn) {
        if (stockIn.listingId() == null) {
            if (stockIn.catalogVariantId() == null) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "catalogVariantId is required for cards kept in hand");
            }
            return new MarketKey(stockIn.catalogVariantId(),
                    stockIn.condition() == null ? CardCondition.NM : stockIn.condition());
        }

        Listing listing = requireListing(seller, stockIn.listingId());
        if (!listing.status().open()) {
            throw closed(listing);
        }
        boolean otherVariant = stockIn.catalogVariantId() != null
                && stockIn.catalogVariantId() != listing.catalogVariantId();
        boolean otherCondition = stockIn.condition() != null && stockIn.condition() != listing.condition();
        if (otherVariant || otherCondition) {
            throw new ConflictException(ErrorCode.LISTING_UNIT_MISMATCH, "Listing #" + listing.id()
                    + " sells variant " + listing.catalogVariantId() + " in " + listing.condition());
        }
        return new MarketKey(listing.catalogVariantId(), listing.condition());
    }

    // ---------- moving cards ----------

    /**
     * Onto another listing of the same printing, condition and seller, or back into
     * the seller's hands when {@code targetListingId} is null. A RETURNED card that
     * is moved is thereby accepted back into sellable stock.
     *
     * <p>Nothing is booked: the card has not left the seller.
     */
    @Transactional
    public ListingUnitResponse move(long userId, UUID unitUid, Long targetListingId) {
        SellerProfile seller = sellers.requireVerifiedSeller(userId);
        ListingUnit unit = lockUnit(seller, unitUid);
        return render(moveLocked(seller, unit, targetListingId));
    }

    /** Takes a card off this listing and back into the seller's hands. */
    @Transactional
    public void removeFromListing(long userId, long listingId, UUID unitUid) {
        SellerProfile seller = sellers.requireVerifiedSeller(userId);
        requireListing(seller, listingId);
        ListingUnit unit = requireOnListing(lockUnit(seller, unitUid), listingId);
        moveLocked(seller, unit, null);
    }

    private ListingUnit moveLocked(SellerProfile seller, ListingUnit unit, Long targetListingId) {
        if (!unit.status().sellerMovable()) {
            throw notMovable(unit);
        }

        if (targetListingId == null) {
            if (unit.status() == ListingUnitStatus.IN_STOCK) {
                return unit;
            }
        } else {
            Listing target = requireListing(seller, targetListingId);
            if (!target.status().open()) {
                throw closed(target);
            }
            if (!target.matches(unit)) {
                throw new ConflictException(ErrorCode.LISTING_UNIT_MISMATCH, "Card " + unit.publicUid() + " is "
                        + unit.condition() + " of variant " + unit.catalogVariantId() + "; listing #" + target.id()
                        + " sells variant " + target.catalogVariantId() + " in " + target.condition());
            }
            if (unit.status() == ListingUnitStatus.LISTED && targetListingId.equals(unit.listingId())) {
                return unit;
            }
        }

        if (!units.place(unit.id(), targetListingId)) {
            throw notMovable(unit);
        }
        return requireUnit(seller, unit.publicUid());
    }

    /** Lost or damaged: out of stock for good, and out of the average at what it cost. */
    @Transactional
    public ListingUnitResponse writeOff(long userId, UUID unitUid, String reason) {
        SellerProfile seller = sellers.requireVerifiedSeller(userId);
        ListingUnit unit = lockUnit(seller, unitUid);

        if (!unit.status().sellerMovable() || !units.writeOff(unit.id())) {
            throw notMovable(unit);
        }
        ledger.lost(unit, reason, userId);
        return render(requireUnit(seller, unitUid));
    }

    /** @param listingId when the card was addressed through a listing, the listing it must be on */
    @Transactional
    public ListingUnitResponse updateNotes(long userId, Long listingId, UUID unitUid, UnitNotes notes) {
        SellerProfile seller = sellers.requireVerifiedSeller(userId);
        ListingUnit unit = requireUnit(seller, unitUid);
        if (listingId != null) {
            requireListing(seller, listingId);
            requireOnListing(unit, listingId);
        }

        units.updateNotes(unit.id(), notes.certNumber(), notes.unitNote(), notes.acquiredAt());
        return render(requireUnit(seller, unitUid));
    }

    // ---------- rules ----------

    private Listing requireListing(SellerProfile seller, long listingId) {
        return listings.findOfSeller(listingId, seller.id())
                .orElseThrow(() -> new NotFoundException(ErrorCode.LISTING_NOT_FOUND));
    }

    private ListingUnit requireUnit(SellerProfile seller, UUID unitUid) {
        return units.findOfSeller(unitUid, seller.id())
                .orElseThrow(() -> new NotFoundException(ErrorCode.LISTING_UNIT_NOT_FOUND));
    }

    private ListingUnit lockUnit(SellerProfile seller, UUID unitUid) {
        return units.lockOfSeller(unitUid, seller.id())
                .orElseThrow(() -> new NotFoundException(ErrorCode.LISTING_UNIT_NOT_FOUND));
    }

    private static ListingUnit requireOnListing(ListingUnit unit, long listingId) {
        if (!Objects.equals(unit.listingId(), listingId)) {
            throw new NotFoundException(ErrorCode.LISTING_UNIT_NOT_FOUND, "Card is not on listing #" + listingId);
        }
        return unit;
    }

    /** New stock needs a printing that is still offered; stock already held is not taken away when one is retired. */
    private void requireActiveVariant(long variantId) {
        CatalogVariant variant = variants.findById(variantId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.VARIANT_NOT_FOUND));
        if (!variant.active()) {
            throw new ConflictException(ErrorCode.VARIANT_INACTIVE);
        }
    }

    private static ConflictException closed(Listing listing) {
        return new ConflictException(ErrorCode.LISTING_CLOSED,
                "Listing #" + listing.id() + " is " + listing.status() + " and takes no cards");
    }

    private static ConflictException notMovable(ListingUnit unit) {
        String why = switch (unit.status()) {
            case RESERVED -> "it is held by an order that has not shipped";
            case SOLD -> "it has left with a parcel";
            case WRITTEN_OFF -> "it is out of stock for good";
            default -> "it changed while this request was on its way";
        };
        return new ConflictException(ErrorCode.LISTING_UNIT_STATE,
                "Card " + unit.publicUid() + " is " + unit.status() + ": " + why);
    }

    // ---------- rendering ----------

    private PageResponse<ListingUnitResponse> page(UnitQuery filters, int page, int size) {
        Paging paging = Paging.of(page, size);
        UnitQuery query = new UnitQuery(filters.sellerProfileId(), filters.listingId(), filters.status(),
                filters.variantId(), filters.condition(), paging.size(), paging.offset());

        return PageResponse.of(render(units.findPage(query)), paging.page(), paging.size(), units.count(query));
    }

    private ListingUnitResponse render(ListingUnit unit) {
        return render(List.of(unit)).getFirst();
    }

    private List<ListingUnitResponse> render(List<ListingUnit> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, ListingCard> cardsByVariant = cards.cards(rows.stream().map(ListingUnit::catalogVariantId).toList());
        return rows.stream().map(u -> ListingUnitResponse.of(u, cardsByVariant.get(u.catalogVariantId()))).toList();
    }

    // ---------- shapes ----------

    /**
     * @param catalogVariantId required when {@code listingId} is null; otherwise it
     *                         may be left out, and must match the listing if given
     * @param condition        likewise; NM when neither it nor a listing says
     */
    public record StockIn(
            Long catalogVariantId,
            CardCondition condition,
            Long listingId,
            int quantity,
            BigDecimal unitCost,
            OffsetDateTime acquiredAt,
            String certNumber,
            String unitNote) {
    }

    public record UnitNotes(String certNumber, String unitNote, OffsetDateTime acquiredAt) {
    }
}
