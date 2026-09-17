package com.pegasus.pegasustcgapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.pegasus.pegasustcgapi.dto.SellerListingResponse;
import com.pegasus.pegasustcgapi.exception.ApiException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.ForbiddenException;
import com.pegasus.pegasustcgapi.model.CardCondition;
import com.pegasus.pegasustcgapi.model.CardEdition;
import com.pegasus.pegasustcgapi.model.CardFinish;
import com.pegasus.pegasustcgapi.model.CatalogVariant;
import com.pegasus.pegasustcgapi.model.Listing;
import com.pegasus.pegasustcgapi.model.ListingStatus;
import com.pegasus.pegasustcgapi.model.ListingUnit;
import com.pegasus.pegasustcgapi.model.ListingUnitStatus;
import com.pegasus.pegasustcgapi.model.MarketKey;
import com.pegasus.pegasustcgapi.model.PricingMode;
import com.pegasus.pegasustcgapi.model.SellerProfile;
import com.pegasus.pegasustcgapi.model.SellerStatus;
import com.pegasus.pegasustcgapi.port.SellerPort;
import com.pegasus.pegasustcgapi.repository.CatalogVariantRepository;
import com.pegasus.pegasustcgapi.repository.ListingImageRepository;
import com.pegasus.pegasustcgapi.repository.ListingPriceHistoryRepository;
import com.pegasus.pegasustcgapi.repository.ListingRepository;
import com.pegasus.pegasustcgapi.repository.ListingRepository.Details;
import com.pegasus.pegasustcgapi.repository.ListingRepository.Pricing;
import com.pegasus.pegasustcgapi.repository.ListingUnitRepository;
import com.pegasus.pegasustcgapi.service.ListingService.PriceChange;
import com.pegasus.pegasustcgapi.storage.StorageService;
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
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ListingServiceTest {

    private static final long USER = 2L;
    private static final long SELLER = 11L;
    private static final long VARIANT = 902L;
    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-09-14T08:00:00Z"), ZoneOffset.UTC);
    private static final OffsetDateTime NOW = OffsetDateTime.now(FIXED);
    private static final MarketKey JP_FOIL_NM = new MarketKey(VARIANT, CardCondition.NM);
    private static final Details NO_DETAILS = new Details(null, null, null, null);

    @Mock
    private SellerPort sellers;

    @Mock
    private ListingRepository listings;

    @Mock
    private ListingUnitRepository units;

    @Mock
    private ListingImageRepository images;

    @Mock
    private ListingPriceHistoryRepository priceHistory;

    @Mock
    private CatalogVariantRepository variants;

    @Mock
    private ListingCardRenderer cards;

    @Mock
    private StorageService storage;

    private ListingService service;

    @BeforeEach
    void setUp() {
        service = new ListingService(sellers, listings, units, images, priceHistory, variants, cards, storage, FIXED);
    }

    // ---------- fixtures ----------

    private static SellerProfile verified() {
        return new SellerProfile(SELLER, USER, SellerStatus.VERIFIED, NOW, null, (short) 2, false, true, NOW);
    }

    private static CatalogVariant variant(boolean active) {
        return new CatalogVariant(VARIANT, 501L, "PKM-SV8A-025-JP-FOIL", "JP", CardFinish.FOIL,
                CardEdition.UNLIMITED, null, null, null, active, NOW);
    }

    private static Listing listing(long id, ListingStatus status, String price, PricingMode mode,
            int available, int reserved) {

        return new Listing(id, SELLER, VARIANT, CardCondition.NM, null, null, new BigDecimal(price), "THB", mode,
                null, null, null, null, available + reserved, reserved, available, status, null, null, 0, null,
                NOW, NOW);
    }

    private static ListingUnit listedUnit(long id, long listingId) {
        return new ListingUnit(id, UUID.randomUUID(), SELLER, VARIANT, CardCondition.NM, listingId,
                ListingUnitStatus.LISTED, null, new BigDecimal("500.00"), NOW, null, null, NOW, NOW);
    }

    private static Pricing manual(String price) {
        return new Pricing(PricingMode.MANUAL, new BigDecimal(price), null, null, null);
    }

    private static ErrorCode codeOf(Throwable e) {
        return ((ApiException) e).errorCode();
    }

    private void asVerifiedSeller() {
        given(sellers.requireVerifiedSeller(USER)).willReturn(verified());
    }

    /** Lets the service read a listing back and render it, as it does after every write. */
    private void rendersAs(Listing listing, Listing... similar) {
        given(listings.findOfSeller(listing.id(), SELLER)).willReturn(Optional.of(listing));
        given(images.findByListing(listing.id())).willReturn(List.of());
        given(listings.findSimilar(SELLER, JP_FOIL_NM, listing.id())).willReturn(List.of(similar));
    }

    // ---------- creating ----------

    @Nested
    class Creating {

        @Test
        @DisplayName("a seller who is not VERIFIED cannot create a listing, and nothing is written")
        void unverifiedSellerIsRefused() {
            given(sellers.requireVerifiedSeller(USER)).willThrow(new ForbiddenException(ErrorCode.SELLER_NOT_VERIFIED));

            assertThatThrownBy(() -> service.create(USER, JP_FOIL_NM, NO_DETAILS, manual("1290.00"), List.of()))
                    .isInstanceOf(ForbiddenException.class)
                    .extracting(ListingServiceTest::codeOf)
                    .isEqualTo(ErrorCode.SELLER_NOT_VERIFIED);

            verifyNoInteractions(listings, priceHistory, images);
        }

        @Test
        @DisplayName("a retired printing cannot be listed")
        void retiredVariantIsRefused() {
            asVerifiedSeller();
            given(variants.findById(VARIANT)).willReturn(Optional.of(variant(false)));

            assertThatThrownBy(() -> service.create(USER, JP_FOIL_NM, NO_DETAILS, manual("1290.00"), List.of()))
                    .extracting(ListingServiceTest::codeOf)
                    .isEqualTo(ErrorCode.VARIANT_INACTIVE);

            verify(listings, never()).insert(anyLong(), any(), any(), any());
        }

        @Test
        @DisplayName("a second listing of the same card and condition is created — and the answer asks about merging")
        void duplicateIsAllowedButFlagged() {
            asVerifiedSeller();
            given(variants.findById(VARIANT)).willReturn(Optional.of(variant(true)));
            given(listings.insert(eq(SELLER), eq(JP_FOIL_NM), any(), any())).willReturn(7004L);
            rendersAs(listing(7004L, ListingStatus.DRAFT, "1290.00", PricingMode.MANUAL, 0, 0),
                    listing(7001L, ListingStatus.ACTIVE, "1290.00", PricingMode.MANUAL, 3, 0));

            SellerListingResponse created = service.create(USER, JP_FOIL_NM, NO_DETAILS, manual("1290.00"), List.of());

            assertThat(created.similarListings()).singleElement().satisfies(twin -> {
                assertThat(twin.id()).isEqualTo(7001L);
                assertThat(twin.samePrice()).isTrue();
            });
            assertThat(created.message("Listing created")).contains("#7001").contains("merge");
            verify(priceHistory).insert(7004L, null, new BigDecimal("1290.00"), PricingMode.MANUAL, USER,
                    "listing created");
        }

        @Test
        @DisplayName("two AUTO_MEDIAN listings of one card are flagged too: the nightly job will price them alike")
        void autoTwinsAreFlagged() {
            asVerifiedSeller();
            given(variants.findById(VARIANT)).willReturn(Optional.of(variant(true)));
            given(listings.insert(eq(SELLER), eq(JP_FOIL_NM), any(), any())).willReturn(7004L);
            rendersAs(listing(7004L, ListingStatus.DRAFT, "1500.00", PricingMode.AUTO_MEDIAN, 0, 0),
                    listing(7001L, ListingStatus.ACTIVE, "1290.00", PricingMode.AUTO_MEDIAN, 3, 0));

            SellerListingResponse created = service.create(USER, JP_FOIL_NM, NO_DETAILS,
                    new Pricing(PricingMode.AUTO_MEDIAN, new BigDecimal("1500.00"), null, null, null), List.of());

            assertThat(created.message("Listing created")).contains("priced alike");
        }

        @Test
        @DisplayName("a grade without the company that gave it is a 400, not the table's CHECK as a 500")
        void gradeNeedsACompany() {
            asVerifiedSeller();
            given(variants.findById(VARIANT)).willReturn(Optional.of(variant(true)));

            assertThatThrownBy(() -> service.create(USER, JP_FOIL_NM,
                    new Details(null, new BigDecimal("10.0"), null, null), manual("1290.00"), List.of()))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("gradingCompany");
        }

        @Test
        @DisplayName("a floor above the ceiling is refused")
        void floorAboveCeiling() {
            asVerifiedSeller();
            given(variants.findById(VARIANT)).willReturn(Optional.of(variant(true)));
            Pricing inverted = new Pricing(PricingMode.AUTO_MEDIAN, new BigDecimal("1290.00"), null,
                    new BigDecimal("1500.00"), new BigDecimal("1000.00"));

            assertThatThrownBy(() -> service.create(USER, JP_FOIL_NM, NO_DETAILS, inverted, List.of()))
                    .hasMessageContaining("autoPriceFloor");
        }

        @Test
        @DisplayName("a photo from another upload purpose is refused before storage is asked")
        void photoMustBeAListingUpload() {
            asVerifiedSeller();
            given(variants.findById(VARIANT)).willReturn(Optional.of(variant(true)));

            assertThatThrownBy(() -> service.create(USER, JP_FOIL_NM, NO_DETAILS, manual("1290.00"),
                    List.of("collections/2026/09/mine.jpg")))
                    .hasMessageContaining("LISTING_IMAGE");

            verifyNoInteractions(storage);
        }

        @Test
        @DisplayName("a photo already on another seller's listing cannot be claimed")
        void photoOfAnotherSellerIsRefused() {
            asVerifiedSeller();
            given(variants.findById(VARIANT)).willReturn(Optional.of(variant(true)));
            given(images.keyUsedByAnotherSeller("listings/2026/09/front.jpg", SELLER)).willReturn(true);

            assertThatThrownBy(() -> service.create(USER, JP_FOIL_NM, NO_DETAILS, manual("1290.00"),
                    List.of("listings/2026/09/front.jpg")))
                    .extracting(ListingServiceTest::codeOf)
                    .isEqualTo(ErrorCode.LISTING_PHOTO_IN_USE);

            verify(listings, never()).insert(anyLong(), any(), any(), any());
        }

        @Test
        @DisplayName("a photo that was never uploaded is refused")
        void photoMustExist() {
            asVerifiedSeller();
            given(variants.findById(VARIANT)).willReturn(Optional.of(variant(true)));
            willThrow(new ApiException(ErrorCode.FILE_NOT_FOUND)).given(storage)
                    .requireUploaded("listings/2026/09/front.jpg");

            assertThatThrownBy(() -> service.create(USER, JP_FOIL_NM, NO_DETAILS, manual("1290.00"),
                    List.of("listings/2026/09/front.jpg")))
                    .extracting(ListingServiceTest::codeOf)
                    .isEqualTo(ErrorCode.FILE_NOT_FOUND);
        }
    }

    // ---------- pricing ----------

    @Nested
    class Repricing {

        @Test
        @DisplayName("a new price is written to history as the seller's own change, old price included")
        void manualChangeIsRecorded() {
            asVerifiedSeller();
            given(listings.lockOfSeller(7001L, SELLER))
                    .willReturn(Optional.of(listing(7001L, ListingStatus.ACTIVE, "600.00", PricingMode.MANUAL, 2, 0)));
            rendersAs(listing(7001L, ListingStatus.ACTIVE, "700.00", PricingMode.MANUAL, 2, 0));

            service.changePrice(USER, 7001L, new PriceChange(PricingMode.MANUAL, new BigDecimal("700.00"), null, null, null));

            verify(listings).updatePricing(eq(7001L), any());
            verify(priceHistory).insert(7001L, new BigDecimal("600.00"), new BigDecimal("700.00"),
                    PricingMode.MANUAL, USER, "set by seller");
        }

        @Test
        @DisplayName("switching to AUTO_MEDIAN without a price keeps the current one, and is not a price change")
        void switchingModeKeepsPrice() {
            asVerifiedSeller();
            given(listings.lockOfSeller(7001L, SELLER))
                    .willReturn(Optional.of(listing(7001L, ListingStatus.ACTIVE, "600.00", PricingMode.MANUAL, 2, 0)));
            rendersAs(listing(7001L, ListingStatus.ACTIVE, "600.00", PricingMode.AUTO_MEDIAN, 2, 0));

            service.changePrice(USER, 7001L,
                    new PriceChange(PricingMode.AUTO_MEDIAN, null, new BigDecimal("-5"), null, null));

            ArgumentCaptor<Pricing> written = ArgumentCaptor.forClass(Pricing.class);
            verify(listings).updatePricing(eq(7001L), written.capture());
            assertThat(written.getValue().price()).isEqualByComparingTo("600.00");
            assertThat(written.getValue().mode()).isEqualTo(PricingMode.AUTO_MEDIAN);
            verifyNoInteractions(priceHistory);
        }

        @Test
        @DisplayName("MANUAL pricing without a price is refused")
        void manualNeedsAPrice() {
            asVerifiedSeller();
            given(listings.lockOfSeller(7001L, SELLER))
                    .willReturn(Optional.of(listing(7001L, ListingStatus.ACTIVE, "600.00", PricingMode.AUTO_MEDIAN, 2, 0)));

            assertThatThrownBy(() -> service.changePrice(USER, 7001L,
                    new PriceChange(PricingMode.MANUAL, null, null, null, null)))
                    .hasMessageContaining("price is required");

            verify(listings, never()).updatePricing(anyLong(), any());
        }

        @Test
        @DisplayName("a DELISTED listing keeps the price it closed at")
        void closedListingKeepsItsPrice() {
            asVerifiedSeller();
            given(listings.lockOfSeller(7001L, SELLER))
                    .willReturn(Optional.of(listing(7001L, ListingStatus.DELISTED, "600.00", PricingMode.MANUAL, 0, 0)));

            assertThatThrownBy(() -> service.changePrice(USER, 7001L,
                    new PriceChange(PricingMode.MANUAL, new BigDecimal("700.00"), null, null, null)))
                    .extracting(ListingServiceTest::codeOf)
                    .isEqualTo(ErrorCode.LISTING_CLOSED);
        }
    }

    // ---------- status ----------

    @Nested
    class Status {

        @Test
        @DisplayName("publishing a draft with no cards on it is refused")
        void emptyDraftCannotPublish() {
            asVerifiedSeller();
            given(listings.findOfSeller(7001L, SELLER))
                    .willReturn(Optional.of(listing(7001L, ListingStatus.DRAFT, "600.00", PricingMode.MANUAL, 0, 0)));
            given(variants.findById(VARIANT)).willReturn(Optional.of(variant(true)));

            assertThatThrownBy(() -> service.changeStatus(USER, 7001L, ListingStatus.ACTIVE))
                    .extracting(ListingServiceTest::codeOf)
                    .isEqualTo(ErrorCode.LISTING_HAS_NO_STOCK);

            verify(listings, never()).activate(anyLong(), any(), any());
        }

        @Test
        @DisplayName("a sold-out listing cannot be forced back on sale; the seller is told it comes back by itself")
        void soldOutComesBackByItself() {
            asVerifiedSeller();
            given(listings.findOfSeller(7001L, SELLER))
                    .willReturn(Optional.of(listing(7001L, ListingStatus.SOLD_OUT, "600.00", PricingMode.MANUAL, 0, 0)));

            assertThatThrownBy(() -> service.changeStatus(USER, 7001L, ListingStatus.ACTIVE))
                    .extracting(ListingServiceTest::codeOf)
                    .isEqualTo(ErrorCode.LISTING_STATUS_TRANSITION);
            assertThatThrownBy(() -> service.changeStatus(USER, 7001L, ListingStatus.ACTIVE))
                    .hasMessageContaining("by itself");
        }

        @Test
        @DisplayName("a listing an admin blocked cannot be resumed by its seller")
        void blockedIsAdminOnly() {
            asVerifiedSeller();
            given(listings.findOfSeller(7001L, SELLER))
                    .willReturn(Optional.of(listing(7001L, ListingStatus.BLOCKED, "600.00", PricingMode.MANUAL, 2, 0)));

            assertThatThrownBy(() -> service.changeStatus(USER, 7001L, ListingStatus.ACTIVE))
                    .hasMessageContaining("blocked by an admin");

            verify(listings, never()).activate(anyLong(), any(), any());
        }

        @Test
        @DisplayName("delisting takes the cards on sale off first, and only then touches the listing")
        void delistLocksCardsBeforeListing() {
            asVerifiedSeller();
            rendersAs(listing(7001L, ListingStatus.ACTIVE, "600.00", PricingMode.MANUAL, 2, 0));
            given(units.lockListedOf(7001L)).willReturn(List.of(listedUnit(1L, 7001L), listedUnit(2L, 7001L)));
            given(listings.changeStatus(eq(7001L), eq(ListingStatus.DELISTED), any())).willReturn(true);

            service.changeStatus(USER, 7001L, ListingStatus.DELISTED);

            InOrder order = inOrder(units, listings);
            order.verify(units).lockListedOf(7001L);
            order.verify(units).detach(List.of(1L, 2L));
            order.verify(listings).changeStatus(eq(7001L), eq(ListingStatus.DELISTED), any());
        }

        @Test
        @DisplayName("losing a race to a concurrent change is a conflict, never a silent success")
        void lostRaceIsAConflict() {
            asVerifiedSeller();
            given(listings.findOfSeller(7001L, SELLER))
                    .willReturn(Optional.of(listing(7001L, ListingStatus.ACTIVE, "600.00", PricingMode.MANUAL, 2, 0)));
            given(listings.changeStatus(eq(7001L), eq(ListingStatus.PAUSED), any())).willReturn(false);

            assertThatThrownBy(() -> service.changeStatus(USER, 7001L, ListingStatus.PAUSED))
                    .extracting(ListingServiceTest::codeOf)
                    .isEqualTo(ErrorCode.LISTING_STATUS_TRANSITION);
        }

        @Test
        @DisplayName("a listing with cards held by an open order cannot be deleted, and no card is moved")
        void reservedListingCannotBeDeleted() {
            asVerifiedSeller();
            given(listings.findOfSeller(7001L, SELLER))
                    .willReturn(Optional.of(listing(7001L, ListingStatus.ACTIVE, "600.00", PricingMode.MANUAL, 1, 1)));

            assertThatThrownBy(() -> service.delete(USER, 7001L))
                    .extracting(ListingServiceTest::codeOf)
                    .isEqualTo(ErrorCode.LISTING_HAS_RESERVATIONS);

            verifyNoInteractions(units);
        }
    }
}
