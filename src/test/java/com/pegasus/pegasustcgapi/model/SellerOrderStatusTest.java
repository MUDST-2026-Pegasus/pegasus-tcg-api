package com.pegasus.pegasustcgapi.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every from/to pair in the state machine, allowed and not [CR-7].
 *
 * <p>The expected table is written out by hand rather than read from the enum:
 * that is what makes this a test. An edit to {@link SellerOrderStatus#targets()}
 * that nobody meant shows up here as a failing pair.
 */
@DisplayName("SellerOrderStatus")
class SellerOrderStatusTest {

    private static Map<SellerOrderStatus, Set<SellerOrderStatus>> expectedTable() {
        Map<SellerOrderStatus, Set<SellerOrderStatus>> table = new EnumMap<>(SellerOrderStatus.class);
        table.put(SellerOrderStatus.PENDING_PAYMENT,
                EnumSet.of(SellerOrderStatus.PAID, SellerOrderStatus.CANCELLED));
        table.put(SellerOrderStatus.PAID,
                EnumSet.of(SellerOrderStatus.PREPARING, SellerOrderStatus.SHIPPED, SellerOrderStatus.CANCELLED));
        table.put(SellerOrderStatus.PREPARING,
                EnumSet.of(SellerOrderStatus.SHIPPED, SellerOrderStatus.CANCELLED));
        table.put(SellerOrderStatus.SHIPPED,
                EnumSet.of(SellerOrderStatus.DELIVERED, SellerOrderStatus.COMPLETED));
        table.put(SellerOrderStatus.DELIVERED,
                EnumSet.of(SellerOrderStatus.COMPLETED));
        // After-sales owns what happens past these; the order module drives none of it.
        table.put(SellerOrderStatus.COMPLETED, EnumSet.noneOf(SellerOrderStatus.class));
        table.put(SellerOrderStatus.CANCELLED, EnumSet.noneOf(SellerOrderStatus.class));
        table.put(SellerOrderStatus.RETURN_REQUESTED, EnumSet.noneOf(SellerOrderStatus.class));
        table.put(SellerOrderStatus.RETURNED, EnumSet.noneOf(SellerOrderStatus.class));
        table.put(SellerOrderStatus.REFUNDED, EnumSet.noneOf(SellerOrderStatus.class));
        return table;
    }

    @Test
    @DisplayName("answers every from/to pair the same way as the state machine table")
    void everyPairMatchesTheTable() {
        Map<SellerOrderStatus, Set<SellerOrderStatus>> expected = expectedTable();
        assertThat(expected.keySet()).containsExactlyInAnyOrder(SellerOrderStatus.values());

        for (SellerOrderStatus from : SellerOrderStatus.values()) {
            for (SellerOrderStatus to : SellerOrderStatus.values()) {
                boolean allowed = expected.get(from).contains(to);
                assertThat(from.canMoveTo(to))
                        .as("%s -> %s", from, to)
                        .isEqualTo(allowed);
            }
        }
    }

    @Test
    @DisplayName("nothing moves to itself")
    void noSelfTransitions() {
        for (SellerOrderStatus status : SellerOrderStatus.values()) {
            assertThat(status.canMoveTo(status)).as("%s -> itself", status).isFalse();
        }
    }

    @Test
    @DisplayName("a terminal status has nowhere left to go")
    void terminalStatusesHaveNoTargets() {
        for (SellerOrderStatus status : SellerOrderStatus.values()) {
            if (status.terminal()) {
                assertThat(status.targets()).as("%s", status).isEmpty();
            }
        }
        assertThat(EnumSet.allOf(SellerOrderStatus.class).stream().filter(SellerOrderStatus::terminal))
                .containsExactlyInAnyOrder(
                        SellerOrderStatus.COMPLETED, SellerOrderStatus.CANCELLED, SellerOrderStatus.REFUNDED);
    }

    @Test
    @DisplayName("pending is PAID or PREPARING and fulfilled is SHIPPED onwards [CR-7]")
    void pendingAndFulfilledGroupings() {
        assertThat(EnumSet.allOf(SellerOrderStatus.class).stream().filter(SellerOrderStatus::pending))
                .containsExactlyInAnyOrder(SellerOrderStatus.PAID, SellerOrderStatus.PREPARING);
        assertThat(EnumSet.allOf(SellerOrderStatus.class).stream().filter(SellerOrderStatus::fulfilled))
                .containsExactlyInAnyOrder(
                        SellerOrderStatus.SHIPPED, SellerOrderStatus.DELIVERED, SellerOrderStatus.COMPLETED);
    }

    @Test
    @DisplayName("the guard sets match the transitions they guard")
    void guardSetsAgreeWithTheTable() {
        assertThat(SellerOrderStatus.CANCELLABLE).allSatisfy(
                status -> assertThat(status.canMoveTo(SellerOrderStatus.CANCELLED)).isTrue());
        assertThat(SellerOrderStatus.CONFIRMABLE).allSatisfy(
                status -> assertThat(status.canMoveTo(SellerOrderStatus.COMPLETED)).isTrue());
        assertThat(SellerOrderStatus.SHIPPABLE).allSatisfy(
                status -> assertThat(status.canMoveTo(SellerOrderStatus.SHIPPED)).isTrue());
    }

    @Test
    @DisplayName("a status name a caller typed is parsed, and anything else is null")
    void parsesCallerSuppliedNames() {
        assertThat(SellerOrderStatus.parseOrNull("paid")).isEqualTo(SellerOrderStatus.PAID);
        assertThat(SellerOrderStatus.parseOrNull("  PREPARING ")).isEqualTo(SellerOrderStatus.PREPARING);
        assertThat(SellerOrderStatus.parseOrNull("SOLD")).isNull();
        assertThat(SellerOrderStatus.parseOrNull("")).isNull();
        assertThat(SellerOrderStatus.parseOrNull(null)).isNull();
    }
}
