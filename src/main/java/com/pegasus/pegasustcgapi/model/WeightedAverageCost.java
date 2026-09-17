package com.pegasus.pegasustcgapi.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * A seller's moving weighted average cost for one printing in one condition — the
 * number that makes profit per order computable [RQ-7, CR-6].
 *
 * <pre>
 * in:  quantity += q;  totalCost += q × unitCost;  average = totalCost / quantity
 * out: totalCost −= q × average;  quantity −= q;  average unchanged
 * </pre>
 *
 * <p>Pure arithmetic on purpose, so the rules can be tested without a database.
 * When the last card leaves, the total is reset to exactly zero: otherwise the
 * rounding of a four-decimal average would leave a few satang behind, and the
 * next purchase would inherit them.
 *
 * @param totalCost       two decimals, like money
 * @param averageUnitCost four decimals, like {@code order_item.unit_cost_snapshot}
 */
public record WeightedAverageCost(int quantity, BigDecimal totalCost, BigDecimal averageUnitCost) {

    public static final WeightedAverageCost EMPTY =
            new WeightedAverageCost(0, BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(4));

    public WeightedAverageCost {
        Objects.requireNonNull(totalCost, "totalCost");
        Objects.requireNonNull(averageUnitCost, "averageUnitCost");
        if (quantity < 0 || totalCost.signum() < 0 || averageUnitCost.signum() < 0) {
            throw new IllegalArgumentException("cost state cannot be negative");
        }
    }

    /** Cards coming in at {@code unitCost} each. */
    public WeightedAverageCost receive(int count, BigDecimal unitCost) {
        if (count <= 0) {
            throw new IllegalArgumentException("count must be positive");
        }
        if (unitCost == null || unitCost.signum() < 0) {
            throw new IllegalArgumentException("inbound stock needs a unit cost of zero or more");
        }
        int newQuantity = Math.addExact(quantity, count);
        BigDecimal newTotal = totalCost.add(unitCost.multiply(BigDecimal.valueOf(count)))
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal newAverage = newTotal.divide(BigDecimal.valueOf(newQuantity), 4, RoundingMode.HALF_UP);
        return new WeightedAverageCost(newQuantity, newTotal, newAverage);
    }

    /**
     * Cards going out. They leave at the current average, which is also what the
     * ledger line records as their cost.
     *
     * @throws IllegalStateException when more would leave than the books say are
     *         held — the ledger and the cards disagree, and guessing would hide it
     */
    public Issued issue(int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("count must be positive");
        }
        if (count > quantity) {
            throw new IllegalStateException(
                    "cost books hold " + quantity + " card(s) but " + count + " are leaving");
        }
        int left = quantity - count;
        BigDecimal consumed = averageUnitCost.multiply(BigDecimal.valueOf(count)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal newTotal = left == 0
                ? BigDecimal.ZERO.setScale(2)
                : totalCost.subtract(consumed).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);

        return new Issued(new WeightedAverageCost(left, newTotal, averageUnitCost),
                averageUnitCost.setScale(2, RoundingMode.HALF_UP));
    }

    /**
     * @param after    the state once the cards are gone
     * @param unitCost what each card left at, rounded to money for the ledger line
     */
    public record Issued(WeightedAverageCost after, BigDecimal unitCost) {
    }
}
