package com.pegasus.pegasustcgapi.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ListingStatusTest {

    @Test
    @DisplayName("a seller publishes a draft, pauses a live listing, resumes a paused one, and can close any of them")
    void sellerMoves() {
        assertThat(ListingStatus.DRAFT.sellerTargets()).containsExactlyInAnyOrder(ListingStatus.ACTIVE, ListingStatus.DELISTED);
        assertThat(ListingStatus.ACTIVE.sellerTargets()).containsExactlyInAnyOrder(ListingStatus.PAUSED, ListingStatus.DELISTED);
        assertThat(ListingStatus.PAUSED.sellerTargets()).containsExactlyInAnyOrder(ListingStatus.ACTIVE, ListingStatus.DELISTED);
        assertThat(ListingStatus.SOLD_OUT.sellerTargets()).containsExactlyInAnyOrder(ListingStatus.PAUSED, ListingStatus.DELISTED);
    }

    @Test
    @DisplayName("SOLD_OUT and BLOCKED are never a seller's to ask for")
    void systemAndAdminStatesAreNotRequestable() {
        Arrays.stream(ListingStatus.values()).forEach(from ->
                assertThat(from.sellerTargets()).doesNotContain(ListingStatus.SOLD_OUT, ListingStatus.BLOCKED));
    }

    @Test
    @DisplayName("DELISTED and BLOCKED are dead ends for the seller, and closed to price and card changes")
    void closedStates() {
        assertThat(ListingStatus.DELISTED.sellerTargets()).isEmpty();
        assertThat(ListingStatus.BLOCKED.sellerTargets()).isEmpty();
        assertThat(Arrays.stream(ListingStatus.values()).filter(s -> !s.open()))
                .containsExactlyInAnyOrder(ListingStatus.DELISTED, ListingStatus.BLOCKED);
    }

    @Test
    @DisplayName("a buyer following a link reaches an active or a sold-out listing, nothing else")
    void publicVisibility() {
        assertThat(Arrays.stream(ListingStatus.values()).filter(ListingStatus::publiclyVisible))
                .containsExactlyInAnyOrder(ListingStatus.ACTIVE, ListingStatus.SOLD_OUT);
    }
}
