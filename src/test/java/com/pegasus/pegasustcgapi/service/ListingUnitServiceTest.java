package com.pegasus.pegasustcgapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.pegasus.pegasustcgapi.exception.ApiException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.model.CardCondition;
import com.pegasus.pegasustcgapi.model.CardEdition;
import com.pegasus.pegasustcgapi.model.CardFinish;
import com.pegasus.pegasustcgapi.model.CatalogVariant;
import com.pegasus.pegasustcgapi.model.Listing;
import com.pegasus.pegasustcgapi.model.ListingStatus;
import com.pegasus.pegasustcgapi.model.ListingUnit;
import com.pegasus.pegasustcgapi.model.ListingUnitStatus;
import com.pegasus.pegasustcgapi.model.PricingMode;
import com.pegasus.pegasustcgapi.model.SellerProfile;
import com.pegasus.pegasustcgapi.model.SellerStatus;
import com.pegasus.pegasustcgapi.port.SellerPort;
import com.pegasus.pegasustcgapi.repository.CatalogVariantRepository;
import com.pegasus.pegasustcgapi.repository.ListingRepository;
import com.pegasus.pegasustcgapi.repository.ListingUnitRepository;
import com.pegasus.pegasustcgapi.repository.ListingUnitRepository.NewUnits;
import com.pegasus.pegasustcgapi.service.ListingUnitService.StockIn;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ListingUnitServiceTest {

    private static final long USER = 2L;
    private static final long SELLER = 11L;
    private static final long JP_FOIL = 902L;
    private static final long EN_NORMAL = 901L;
    private static final UUID CARD = UUID.fromString("8f14c0d2-0000-4000-8000-000000004b71");
    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-09-14T08:00:00Z"), ZoneOffset.UTC);
    private static final OffsetDateTime NOW = OffsetDateTime.now(FIXED);

    @Mock
    private SellerPort sellers;

    @Mock
    private ListingRepository listings;

    @Mock
    private ListingUnitRepository units;

    @Mock
    private CatalogVariantRepository variants;

    @Mock
    private InventoryLedger ledger;

    @Mock
    private ListingCardRenderer cards;

    private ListingUnitService service;

    @BeforeEach
    void setUp() {
        service = new ListingUnitService(sellers, listings, units, variants, ledger, cards, FIXED);
    }

    // ---------- fixtures ----------

    private static SellerProfile verified() {
        return new SellerProfile(SELLER, USER, SellerStatus.VERIFIED, NOW, null, (short) 2, false, true, NOW);
    }

    private static ListingUnit card(ListingUnitStatus status, Long listingId) {
        return new ListingUnit(7601L, CARD, SELLER, JP_FOIL, CardCondition.NM, listingId, status, null,
                new BigDecimal("900.00"), NOW, null, null, NOW, NOW);
    }

    private static Listing listing(long id, long variantId, CardCondition condition, ListingStatus status) {
        return new Listing(id, SELLER, variantId, condition, null, null, new BigDecimal("1290.00"), "THB",
                PricingMode.MANUAL, null, null, null, null, 1, 0, 1, status, null, null, 0, null, NOW, NOW);
    }

    private static CatalogVariant activeVariant(long id) {
        return new CatalogVariant(id, 501L, "PKM-" + id, "JP", CardFinish.FOIL, CardEdition.UNLIMITED,
                null, null, null, true, NOW);
    }

    private static ErrorCode codeOf(Throwable e) {
        return ((ApiException) e).errorCode();
    }

    // ---------- moving ----------

    @Nested
    class Moving {

        @BeforeEach
        void verifiedSeller() {
            given(sellers.requireVerifiedSeller(USER)).willReturn(verified());
        }

        @Test
        @DisplayName("a Japanese foil cannot join an English listing; refused before the database trigger has to")
        void differentPrintingIsAMismatch() {
            given(units.lockOfSeller(CARD, SELLER)).willReturn(Optional.of(card(ListingUnitStatus.LISTED, 7001L)));
            given(listings.findOfSeller(7002L, SELLER))
                    .willReturn(Optional.of(listing(7002L, EN_NORMAL, CardCondition.NM, ListingStatus.ACTIVE)));

            assertThatThrownBy(() -> service.move(USER, CARD, 7002L))
                    .extracting(ListingUnitServiceTest::codeOf)
                    .isEqualTo(ErrorCode.LISTING_UNIT_MISMATCH);

            verify(units, never()).place(anyLong(), any());
        }

        @Test
        @DisplayName("a card in another condition cannot join the listing either")
        void differentConditionIsAMismatch() {
            given(units.lockOfSeller(CARD, SELLER)).willReturn(Optional.of(card(ListingUnitStatus.IN_STOCK, null)));
            given(listings.findOfSeller(7002L, SELLER))
                    .willReturn(Optional.of(listing(7002L, JP_FOIL, CardCondition.LP, ListingStatus.ACTIVE)));

            assertThatThrownBy(() -> service.move(USER, CARD, 7002L))
                    .extracting(ListingUnitServiceTest::codeOf)
                    .isEqualTo(ErrorCode.LISTING_UNIT_MISMATCH);
        }

        @Test
        @DisplayName("a card held by an open order cannot be moved, and no listing is even looked at")
        void reservedCardStaysPut() {
            given(units.lockOfSeller(CARD, SELLER)).willReturn(Optional.of(card(ListingUnitStatus.RESERVED, 7001L)));

            assertThatThrownBy(() -> service.move(USER, CARD, 7004L))
                    .extracting(ListingUnitServiceTest::codeOf)
                    .isEqualTo(ErrorCode.LISTING_UNIT_STATE);

            verifyNoInteractions(listings, ledger);
        }

        @Test
        @DisplayName("a card cannot join a listing its seller has closed")
        void closedListingTakesNoCards() {
            given(units.lockOfSeller(CARD, SELLER)).willReturn(Optional.of(card(ListingUnitStatus.IN_STOCK, null)));
            given(listings.findOfSeller(7004L, SELLER))
                    .willReturn(Optional.of(listing(7004L, JP_FOIL, CardCondition.NM, ListingStatus.DELISTED)));

            assertThatThrownBy(() -> service.move(USER, CARD, 7004L))
                    .extracting(ListingUnitServiceTest::codeOf)
                    .isEqualTo(ErrorCode.LISTING_CLOSED);
        }

        @Test
        @DisplayName("moving to a matching listing keeps the card's UUID and books nothing in the ledger")
        void matchingMoveKeepsIdentity() {
            given(units.lockOfSeller(CARD, SELLER)).willReturn(Optional.of(card(ListingUnitStatus.LISTED, 7001L)));
            given(listings.findOfSeller(7004L, SELLER))
                    .willReturn(Optional.of(listing(7004L, JP_FOIL, CardCondition.NM, ListingStatus.ACTIVE)));
            given(units.place(7601L, 7004L)).willReturn(true);
            given(units.findOfSeller(CARD, SELLER)).willReturn(Optional.of(card(ListingUnitStatus.LISTED, 7004L)));

            assertThat(service.move(USER, CARD, 7004L).publicUid()).isEqualTo(CARD);

            verifyNoInteractions(ledger);
        }

        @Test
        @DisplayName("a card can only be taken off a listing it is actually on")
        void removeThroughTheWrongListingIsNotFound() {
            given(listings.findOfSeller(7004L, SELLER))
                    .willReturn(Optional.of(listing(7004L, JP_FOIL, CardCondition.NM, ListingStatus.ACTIVE)));
            given(units.lockOfSeller(CARD, SELLER)).willReturn(Optional.of(card(ListingUnitStatus.LISTED, 7001L)));

            assertThatThrownBy(() -> service.removeFromListing(USER, 7004L, CARD))
                    .extracting(ListingUnitServiceTest::codeOf)
                    .isEqualTo(ErrorCode.LISTING_UNIT_NOT_FOUND);

            verify(units, never()).place(anyLong(), any());
        }

        @Test
        @DisplayName("a sold card cannot be written off, and the ledger is not touched")
        void soldCardCannotBeWrittenOff() {
            given(units.lockOfSeller(CARD, SELLER)).willReturn(Optional.of(card(ListingUnitStatus.SOLD, 7001L)));

            assertThatThrownBy(() -> service.writeOff(USER, CARD, "lost"))
                    .extracting(ListingUnitServiceTest::codeOf)
                    .isEqualTo(ErrorCode.LISTING_UNIT_STATE);

            verifyNoInteractions(ledger);
        }

        @Test
        @DisplayName("writing off a card books it as lost, with the reason")
        void writeOffIsBookedAsLoss() {
            ListingUnit inHand = card(ListingUnitStatus.IN_STOCK, null);
            given(units.lockOfSeller(CARD, SELLER)).willReturn(Optional.of(inHand));
            given(units.writeOff(7601L)).willReturn(true);
            given(units.findOfSeller(CARD, SELLER)).willReturn(Optional.of(card(ListingUnitStatus.WRITTEN_OFF, null)));

            service.writeOff(USER, CARD, "water damage");

            verify(ledger).lost(inHand, "water damage", USER);
        }
    }

    // ---------- stocking ----------

    @Nested
    class Stocking {

        @BeforeEach
        void verifiedSeller() {
            given(sellers.requireVerifiedSeller(USER)).willReturn(verified());
        }

        @Test
        @DisplayName("three cards in hand become three rows, and the ledger books them at their cost")
        void stockInBooksEveryCard() {
            given(variants.findById(JP_FOIL)).willReturn(Optional.of(activeVariant(JP_FOIL)));
            List<ListingUnit> created = List.of(card(ListingUnitStatus.IN_STOCK, null),
                    card(ListingUnitStatus.IN_STOCK, null), card(ListingUnitStatus.IN_STOCK, null));
            given(units.insert(any())).willReturn(created);

            service.stockIn(USER, new StockIn(JP_FOIL, CardCondition.NM, null, 3, new BigDecimal("500.00"),
                    null, null, null));

            ArgumentCaptor<NewUnits> inserted = ArgumentCaptor.forClass(NewUnits.class);
            verify(units).insert(inserted.capture());
            assertThat(inserted.getValue().quantity()).isEqualTo(3);
            assertThat(inserted.getValue().listingId()).isNull();
            assertThat(inserted.getValue().acquiredAt()).isEqualTo(NOW);
            verify(ledger).stockIn(created, new BigDecimal("500.00"), USER);
        }

        @Test
        @DisplayName("a certified slab is one card: a cert number with quantity 3 is refused")
        void certNumberMeansOneCard() {
            given(variants.findById(JP_FOIL)).willReturn(Optional.of(activeVariant(JP_FOIL)));

            assertThatThrownBy(() -> service.stockIn(USER, new StockIn(JP_FOIL, CardCondition.NM, null, 3,
                    new BigDecimal("500.00"), null, "PSA-12345678", null)))
                    .hasMessageContaining("quantity 1");

            verify(units, never()).insert(any());
        }

        @Test
        @DisplayName("cards put on a listing must be the listing's condition; naming another is not quietly ignored")
        void stockOntoListingMustMatch() {
            given(listings.findOfSeller(7001L, SELLER))
                    .willReturn(Optional.of(listing(7001L, JP_FOIL, CardCondition.NM, ListingStatus.ACTIVE)));

            assertThatThrownBy(() -> service.stockIn(USER, new StockIn(null, CardCondition.LP, 7001L, 1,
                    new BigDecimal("500.00"), null, null, null)))
                    .extracting(ListingUnitServiceTest::codeOf)
                    .isEqualTo(ErrorCode.LISTING_UNIT_MISMATCH);

            verifyNoInteractions(ledger);
        }

        @Test
        @DisplayName("cards kept in hand need a printing named")
        void inHandNeedsAVariant() {
            assertThatThrownBy(() -> service.stockIn(USER, new StockIn(null, null, null, 1,
                    new BigDecimal("500.00"), null, null, null)))
                    .hasMessageContaining("catalogVariantId");
        }
    }
}
