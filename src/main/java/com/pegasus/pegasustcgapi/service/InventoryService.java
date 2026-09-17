package com.pegasus.pegasustcgapi.service;

import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.common.Paging;
import com.pegasus.pegasustcgapi.dto.InventoryMovementResponse;
import com.pegasus.pegasustcgapi.dto.ListingCard;
import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.model.CardCondition;
import com.pegasus.pegasustcgapi.model.InventoryMovement;
import com.pegasus.pegasustcgapi.model.ListingUnit;
import com.pegasus.pegasustcgapi.model.ListingUnitStatus;
import com.pegasus.pegasustcgapi.model.MarketKey;
import com.pegasus.pegasustcgapi.model.MovementType;
import com.pegasus.pegasustcgapi.model.SellerProfile;
import com.pegasus.pegasustcgapi.model.WeightedAverageCost;
import com.pegasus.pegasustcgapi.port.InventoryPort;
import com.pegasus.pegasustcgapi.port.SellerPort;
import com.pegasus.pegasustcgapi.repository.InventoryMovementRepository;
import com.pegasus.pegasustcgapi.repository.InventoryMovementRepository.MovementQuery;
import com.pegasus.pegasustcgapi.repository.ListingUnitRepository;
import com.pegasus.pegasustcgapi.repository.SellerVariantCostRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stock as the order module drives it, through {@link InventoryPort}, and the
 * stock ledger as the seller reads it.
 *
 * <p>A failure inside a port method marks the caller's transaction rollback-only:
 * a checkout that cannot reserve does not get to carry on and commit half an order.
 */
@Service
public class InventoryService implements InventoryPort {

    private final SellerPort sellers;
    private final ListingUnitRepository units;
    private final InventoryMovementRepository movements;
    private final SellerVariantCostRepository costs;
    private final InventoryLedger ledger;
    private final ListingCardRenderer cards;
    private final Clock clock;

    public InventoryService(
            SellerPort sellers,
            ListingUnitRepository units,
            InventoryMovementRepository movements,
            SellerVariantCostRepository costs,
            InventoryLedger ledger,
            ListingCardRenderer cards,
            Clock clock) {

        this.sellers = sellers;
        this.units = units;
        this.movements = movements;
        this.costs = costs;
        this.ledger = ledger;
        this.cards = cards;
        this.clock = clock;
    }

    // ---------- the seller's ledger ----------

    /** Newest first. {@code unitUid} gives one card's whole in-and-out history. */
    public PageResponse<InventoryMovementResponse> movements(long userId, Long variantId, CardCondition condition,
            MovementType type, Long listingId, UUID unitUid, int page, int size) {

        SellerProfile seller = sellers.requireProfile(userId);
        Paging paging = Paging.of(page, size);
        MovementQuery query = new MovementQuery(seller.id(), variantId, condition, type, listingId, unitUid,
                paging.size(), paging.offset());

        List<InventoryMovement> rows = movements.findPage(query);
        Map<Long, ListingCard> cardsByVariant = cards.cards(
                rows.stream().map(InventoryMovement::catalogVariantId).toList());

        List<InventoryMovementResponse> items = rows.stream()
                .map(m -> InventoryMovementResponse.of(m, cardsByVariant.get(m.catalogVariantId())))
                .toList();
        return PageResponse.of(items, paging.page(), paging.size(), movements.count(query));
    }

    // ---------- InventoryPort ----------

    @Override
    @Transactional
    public Map<Long, List<ReservedUnit>> reserve(Map<Long, Integer> quantityByListing) {
        quantityByListing.forEach((listingId, quantity) -> {
            if (quantity == null || quantity <= 0) {
                throw new IllegalArgumentException("quantity for listing " + listingId + " must be positive");
            }
        });

        Map<Long, List<ReservedUnit>> held = new LinkedHashMap<>();
        for (Map.Entry<Long, Integer> line : new TreeMap<>(quantityByListing).entrySet()) {
            long listingId = line.getKey();
            int quantity = line.getValue();

            List<ListingUnit> picked = units.lockForReservation(listingId, quantity);
            if (picked.size() < quantity) {
                throw new ConflictException(ErrorCode.INSUFFICIENT_STOCK,
                        "Listing #" + listingId + " cannot supply " + quantity + " card(s) right now");
            }

            units.changeStatus(ids(picked), ListingUnitStatus.LISTED, ListingUnitStatus.RESERVED);
            held.put(listingId, picked.stream().map(u -> new ReservedUnit(u.id(), u.publicUid())).toList());
        }
        return held;
    }

    @Override
    @Transactional
    public void release(Collection<Long> unitIds) {
        List<ListingUnit> held = units.lockByIds(unitIds).stream()
                .filter(u -> u.status() == ListingUnitStatus.RESERVED)
                .toList();

        // One listing at a time, in id order, so the recount trigger takes listing locks in a fixed order.
        for (List<Long> group : idsByListing(held)) {
            units.release(group);
        }
    }

    @Override
    @Transactional
    public List<SoldUnit> commitSale(long orderItemId, Collection<Long> unitIds, Long actorUserId) {
        List<ListingUnit> held = units.lockByIds(unitIds);
        requireAll(unitIds, held, ListingUnitStatus.RESERVED);

        OffsetDateTime now = OffsetDateTime.now(clock);
        for (List<Long> group : idsByListing(held)) {
            units.markSold(group, now);
        }

        Map<Long, BigDecimal> costByUnit = ledger.sold(held, orderItemId, actorUserId);
        return held.stream()
                .map(u -> new SoldUnit(u.id(), u.publicUid(), costByUnit.get(u.id())))
                .toList();
    }

    @Override
    @Transactional
    public void restockReturn(long returnItemId, long unitId, Long actorUserId) {
        List<ListingUnit> locked = units.lockByIds(List.of(unitId));
        requireAll(List.of(unitId), locked, ListingUnitStatus.SOLD);
        ListingUnit unit = locked.getFirst();

        // The cost it left at keeps the average where it was before the sale.
        BigDecimal cost = movements.lastSaleCost(unitId)
                .or(() -> Optional.ofNullable(unit.acquisitionCost()))
                .orElse(BigDecimal.ZERO);

        units.markReturned(unitId);
        ledger.returned(unit, cost, returnItemId, actorUserId);
    }

    @Override
    public BigDecimal averageUnitCost(long sellerProfileId, long catalogVariantId, CardCondition condition) {
        return costs.find(sellerProfileId, new MarketKey(catalogVariantId, condition))
                .map(WeightedAverageCost::averageUnitCost)
                .orElse(WeightedAverageCost.EMPTY.averageUnitCost());
    }

    // ---------- helpers ----------

    private static void requireAll(Collection<Long> requested, List<ListingUnit> found, ListingUnitStatus expected) {
        if (found.size() != new HashSet<>(requested).size()) {
            throw new NotFoundException(ErrorCode.LISTING_UNIT_NOT_FOUND, "Some of the cards named do not exist");
        }
        for (ListingUnit unit : found) {
            if (unit.status() != expected) {
                throw new ConflictException(ErrorCode.LISTING_UNIT_STATE,
                        "Card " + unit.publicUid() + " is " + unit.status() + ", not " + expected);
            }
        }
    }

    private static Collection<List<Long>> idsByListing(List<ListingUnit> rows) {
        TreeMap<Long, List<Long>> groups = new TreeMap<>(Comparator.nullsFirst(Comparator.naturalOrder()));
        for (ListingUnit unit : rows) {
            groups.computeIfAbsent(unit.listingId(), k -> new ArrayList<>()).add(unit.id());
        }
        return groups.values();
    }

    private static List<Long> ids(List<ListingUnit> rows) {
        return rows.stream().map(ListingUnit::id).toList();
    }
}
