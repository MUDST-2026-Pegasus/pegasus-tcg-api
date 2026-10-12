package com.pegasus.pegasustcgapi.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class ReturnStatusTest {

    @Test
    void onlyDocumentedStateTransitionsAreAllowed() {
        assertThat(ReturnStatus.REQUESTED.targets()).isEqualTo(EnumSet.of(
                ReturnStatus.APPROVED, ReturnStatus.REJECTED));
        assertThat(ReturnStatus.APPROVED.targets()).isEqualTo(EnumSet.of(
                ReturnStatus.AWAITING_RETURN_SHIPMENT, ReturnStatus.REFUNDED));
        assertThat(ReturnStatus.AWAITING_RETURN_SHIPMENT.targets())
                .containsExactly(ReturnStatus.IN_TRANSIT);
        assertThat(ReturnStatus.IN_TRANSIT.targets()).containsExactly(ReturnStatus.RECEIVED);
        assertThat(ReturnStatus.RECEIVED.targets()).containsExactly(ReturnStatus.REFUNDED);
        assertThat(ReturnStatus.REFUNDED.targets()).containsExactly(ReturnStatus.CLOSED);
        assertThat(ReturnStatus.REJECTED.targets()).isEmpty();
        assertThat(ReturnStatus.CLOSED.targets()).isEmpty();
    }
}
