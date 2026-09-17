package com.pegasus.pegasustcgapi.repository;

import static com.pegasus.pegasustcgapi.jooq.tables.SellerVariantCost.SELLER_VARIANT_COST;

import com.pegasus.pegasustcgapi.jooq.tables.records.SellerVariantCostRecord;
import com.pegasus.pegasustcgapi.model.MarketKey;
import com.pegasus.pegasustcgapi.model.WeightedAverageCost;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/**
 * Reads and writes {@code seller_variant_cost}. The arithmetic lives in
 * {@link WeightedAverageCost}; this only loads a row under lock and saves it back.
 *
 * <p>Cost rows are always the last thing a transaction locks, after cards and
 * listings, and several are always taken in a fixed order — so they never close
 * a deadlock cycle.
 */
@Repository
public class SellerVariantCostRepository {

    private final DSLContext dsl;

    public SellerVariantCostRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * Locks the row, creating an empty one first when this seller never stocked
     * the printing in this condition. Two first purchases racing each other both
     * end up on the one row: the second insert waits for the first and then does
     * nothing.
     *
     * @return the cost state, and whether this call created the row
     */
    public Locked lockOrCreate(long sellerProfileId, MarketKey key) {
        int created = dsl.insertInto(SELLER_VARIANT_COST)
                .set(SELLER_VARIANT_COST.SELLER_PROFILE_ID, sellerProfileId)
                .set(SELLER_VARIANT_COST.CATALOG_VARIANT_ID, key.catalogVariantId())
                .set(SELLER_VARIANT_COST.CONDITION_CODE, key.condition().name())
                .onConflict(SELLER_VARIANT_COST.SELLER_PROFILE_ID, SELLER_VARIANT_COST.CATALOG_VARIANT_ID,
                        SELLER_VARIANT_COST.CONDITION_CODE)
                .doNothing()
                .execute();

        WeightedAverageCost cost = lock(sellerProfileId, key)
                .orElseThrow(() -> new IllegalStateException("cost row vanished right after its upsert"));
        return new Locked(cost, created > 0);
    }

    public Optional<WeightedAverageCost> lock(long sellerProfileId, MarketKey key) {
        return dsl.selectFrom(SELLER_VARIANT_COST)
                .where(keyIs(sellerProfileId, key))
                .forNoKeyUpdate()
                .fetchOptional()
                .map(SellerVariantCostRepository::toCost);
    }

    public Optional<WeightedAverageCost> find(long sellerProfileId, MarketKey key) {
        return dsl.selectFrom(SELLER_VARIANT_COST)
                .where(keyIs(sellerProfileId, key))
                .fetchOptional()
                .map(SellerVariantCostRepository::toCost);
    }

    public void save(long sellerProfileId, MarketKey key, WeightedAverageCost cost, OffsetDateTime at) {
        dsl.update(SELLER_VARIANT_COST)
                .set(SELLER_VARIANT_COST.TOTAL_QUANTITY, cost.quantity())
                .set(SELLER_VARIANT_COST.TOTAL_COST, cost.totalCost())
                .set(SELLER_VARIANT_COST.AVERAGE_UNIT_COST, cost.averageUnitCost())
                .set(SELLER_VARIANT_COST.LAST_MOVEMENT_AT, at)
                .where(keyIs(sellerProfileId, key))
                .execute();
    }

    private static Condition keyIs(long sellerProfileId, MarketKey key) {
        return SELLER_VARIANT_COST.SELLER_PROFILE_ID.eq(sellerProfileId)
                .and(SELLER_VARIANT_COST.CATALOG_VARIANT_ID.eq(key.catalogVariantId()))
                .and(SELLER_VARIANT_COST.CONDITION_CODE.eq(key.condition().name()));
    }

    private static WeightedAverageCost toCost(SellerVariantCostRecord r) {
        return new WeightedAverageCost(r.getTotalQuantity(), r.getTotalCost(), r.getAverageUnitCost());
    }

    /** @param created true when this seller never had cost on the books for the key before */
    public record Locked(WeightedAverageCost cost, boolean created) {
    }
}
