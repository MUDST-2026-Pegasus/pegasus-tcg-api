package com.pegasus.pegasustcgapi.service;

import com.pegasus.pegasustcgapi.model.CardCondition;
import com.pegasus.pegasustcgapi.model.InventoryMovement;
import com.pegasus.pegasustcgapi.model.ListingUnit;
import com.pegasus.pegasustcgapi.model.MarketKey;
import com.pegasus.pegasustcgapi.model.MovementType;
import com.pegasus.pegasustcgapi.model.WeightedAverageCost;
import com.pegasus.pegasustcgapi.repository.InventoryMovementRepository;
import com.pegasus.pegasustcgapi.repository.InventoryMovementRepository.NewMovement;
import com.pegasus.pegasustcgapi.repository.SellerVariantCostRepository;
import com.pegasus.pegasustcgapi.repository.SellerVariantCostRepository.Locked;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * Books stock in and out: one ledger line per card, and the seller's average cost
 * moved to match [CR-8, RQ-7].
 *
 * <p>The ledger and the cost books count exactly the cards that are
 * {@link com.pegasus.pegasustcgapi.model.ListingUnitStatus#onHand() on hand}.
 * Moving a card between listings, or holding it for an order, books nothing: the
 * card has not left the seller. Only arriving, leaving with a parcel, coming back
 * and being written off do.
 *
 * <p>Always called after the cards themselves are updated, so the cost row is the
 * last lock a transaction takes.
 */
@Component
public class InventoryLedger {

    private static final Comparator<CostKey> COST_ORDER = Comparator
            .comparingLong(CostKey::sellerProfileId)
            .thenComparingLong(CostKey::catalogVariantId)
            .thenComparing(CostKey::condition);

    private final InventoryMovementRepository movements;
    private final SellerVariantCostRepository costs;
    private final Clock clock;

    public InventoryLedger(InventoryMovementRepository movements, SellerVariantCostRepository costs, Clock clock) {
        this.movements = movements;
        this.costs = costs;
        this.clock = clock;
    }

    /**
     * New cards from outside, all of one seller, printing and condition.
     *
     * @return INITIAL_STOCK the first time this seller ever stocks this card in this
     *         condition, RESTOCK after that
     */
    public MovementType stockIn(List<ListingUnit> units, BigDecimal unitCost, Long actorUserId) {
        ListingUnit first = units.getFirst();
        MarketKey key = keyOf(first);

        Locked locked = costs.lockOrCreate(first.sellerProfileId(), key);
        MovementType type = locked.created() ? MovementType.INITIAL_STOCK : MovementType.RESTOCK;
        costs.save(first.sellerProfileId(), key, locked.cost().receive(units.size(), unitCost), now());

        movements.insertAll(units.stream()
                .map(u -> line(u, type, 1, unitCost, null, null, null, actorUserId))
                .toList());
        return type;
    }

    /** A card back from a buyer, booked in at the cost it left at. */
    public void returned(ListingUnit unit, BigDecimal unitCost, long returnItemId, Long actorUserId) {
        MarketKey key = keyOf(unit);
        Locked locked = costs.lockOrCreate(unit.sellerProfileId(), key);
        costs.save(unit.sellerProfileId(), key, locked.cost().receive(1, unitCost), now());

        movements.insertAll(List.of(line(unit, MovementType.RETURN_RESTOCK, 1, unitCost,
                InventoryMovement.REF_RETURN_ITEM, returnItemId, null, actorUserId)));
    }

    /**
     * Cards that left with a parcel.
     *
     * @return the cost each card left at, by unit id
     */
    public Map<Long, BigDecimal> sold(List<ListingUnit> units, long orderItemId, Long actorUserId) {
        return issue(units, MovementType.SALE, InventoryMovement.REF_ORDER_ITEM, orderItemId, null, actorUserId);
    }

    /** @return the cost the card was written off at */
    public BigDecimal lost(ListingUnit unit, String note, Long actorUserId) {
        return issue(List.of(unit), MovementType.LOSS, null, null, note, actorUserId).get(unit.id());
    }

    /**
     * Cards leaving, grouped by seller and market and taken in a fixed order, so
     * two transactions that each touch several cost rows lock them alike.
     */
    private Map<Long, BigDecimal> issue(List<ListingUnit> units, MovementType type, String referenceType,
            Long referenceId, String note, Long actorUserId) {

        TreeMap<CostKey, List<ListingUnit>> groups = new TreeMap<>(COST_ORDER);
        for (ListingUnit unit : units) {
            groups.computeIfAbsent(CostKey.of(unit), k -> new ArrayList<>()).add(unit);
        }

        Map<Long, BigDecimal> costByUnit = new HashMap<>();
        List<NewMovement> lines = new ArrayList<>();

        groups.forEach((key, group) -> {
            MarketKey market = new MarketKey(key.catalogVariantId(), key.condition());
            WeightedAverageCost held = costs.lock(key.sellerProfileId(), market)
                    .orElseThrow(() -> new IllegalStateException("No cost on the books for seller "
                            + key.sellerProfileId() + ", variant " + key.catalogVariantId() + " " + key.condition()
                            + ": these cards never came in through the ledger"));

            WeightedAverageCost.Issued issued = held.issue(group.size());
            costs.save(key.sellerProfileId(), market, issued.after(), now());

            for (ListingUnit unit : group) {
                costByUnit.put(unit.id(), issued.unitCost());
                lines.add(line(unit, type, -1, issued.unitCost(), referenceType, referenceId, note, actorUserId));
            }
        });

        movements.insertAll(lines);
        return costByUnit;
    }

    /** The listing on the line is the one the card was on when it moved, so a group's history reads back. */
    private static NewMovement line(ListingUnit unit, MovementType type, int delta, BigDecimal unitCost,
            String referenceType, Long referenceId, String note, Long actorUserId) {

        return new NewMovement(unit.sellerProfileId(), unit.listingId(), unit.id(), unit.catalogVariantId(),
                unit.condition(), type, delta, unitCost, referenceType, referenceId, note, actorUserId);
    }

    private static MarketKey keyOf(ListingUnit unit) {
        return new MarketKey(unit.catalogVariantId(), unit.condition());
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    private record CostKey(long sellerProfileId, long catalogVariantId, CardCondition condition) {

        static CostKey of(ListingUnit unit) {
            return new CostKey(unit.sellerProfileId(), unit.catalogVariantId(), unit.condition());
        }
    }
}
