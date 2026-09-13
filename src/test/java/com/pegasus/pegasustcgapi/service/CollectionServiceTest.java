package com.pegasus.pegasustcgapi.service;

import static com.pegasus.pegasustcgapi.support.AuthUserBuilder.anActiveUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.pegasus.pegasustcgapi.dto.CollectionItemResponse;
import com.pegasus.pegasustcgapi.dto.PublicCollectionItemResponse;
import com.pegasus.pegasustcgapi.exception.ApiException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.model.CardCondition;
import com.pegasus.pegasustcgapi.model.CardEdition;
import com.pegasus.pegasustcgapi.model.CardFinish;
import com.pegasus.pegasustcgapi.model.CatalogProduct;
import com.pegasus.pegasustcgapi.model.CatalogVariant;
import com.pegasus.pegasustcgapi.model.CollectionItem;
import com.pegasus.pegasustcgapi.model.CollectionSource;
import com.pegasus.pegasustcgapi.model.ProductType;
import com.pegasus.pegasustcgapi.model.UserStatus;
import com.pegasus.pegasustcgapi.repository.CatalogImageRepository;
import com.pegasus.pegasustcgapi.repository.CatalogProductRepository;
import com.pegasus.pegasustcgapi.repository.CatalogVariantRepository;
import com.pegasus.pegasustcgapi.repository.CollectionItemRepository;
import com.pegasus.pegasustcgapi.repository.CollectionItemRepository.CollectionItemFields;
import com.pegasus.pegasustcgapi.repository.CollectionItemRepository.CollectionQuery;
import com.pegasus.pegasustcgapi.repository.UserRepository;
import com.pegasus.pegasustcgapi.storage.StorageService;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CollectionServiceTest {

    private static final long OWNER = 7L;
    private static final long STRANGER = 8L;
    private static final long VARIANT = 901L;
    private static final long RETIRED_VARIANT = 902L;
    private static final long PRODUCT = 501L;
    private static final String PHOTO = "collections/2026/09/mine.jpg";
    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-09-13T08:00:00Z"), ZoneOffset.UTC);

    @Mock
    private CollectionItemRepository items;

    @Mock
    private CatalogVariantRepository variants;

    @Mock
    private CatalogProductRepository products;

    @Mock
    private CatalogImageRepository catalogImages;

    @Mock
    private UserRepository users;

    @Mock
    private StorageService storage;

    private CollectionService service;

    @BeforeEach
    void setUp() {
        service = new CollectionService(items, variants, products, catalogImages, users, storage, FIXED);
    }

    // ---------- fixtures ----------

    private static CatalogVariant variant(long id, boolean active) {
        return new CatalogVariant(id, PRODUCT, "PKM-" + id, "EN", CardFinish.FOIL, CardEdition.UNLIMITED,
                null, null, null, active, OffsetDateTime.now(FIXED));
    }

    private static CatalogProduct pikachu() {
        return new CatalogProduct(PRODUCT, (short) 1, 1, null, ProductType.SINGLE_CARD, "Pikachu ex", null,
                "pikachu-ex-025-187", "025/187", "RR", null, Map.of(), true, null,
                OffsetDateTime.now(FIXED), OffsetDateTime.now(FIXED));
    }

    private static CollectionItem item(long id, long variantId, int quantity, CollectionSource source, String photo) {
        return new CollectionItem(id, OWNER, variantId, CardCondition.NM, quantity, source, null, null, null,
                new BigDecimal("1290.00"), null, photo, "from Comic Con", true,
                OffsetDateTime.now(FIXED), OffsetDateTime.now(FIXED));
    }

    private static CollectionItemFields fields(long variantId, int quantity) {
        return new CollectionItemFields(variantId, CardCondition.NM, quantity, null, null, null,
                new BigDecimal("1290.00"), null, null, "from Comic Con", true);
    }

    private static CollectionItemFields withPhoto(String photo) {
        return new CollectionItemFields(VARIANT, CardCondition.NM, 1, null, null, null,
                null, null, photo, null, false);
    }

    /** Lets a row be read back and rendered with its card, as the service does after a write. */
    private void rowRendersAs(CollectionItem row) {
        given(items.findOfUser(row.id(), OWNER)).willReturn(Optional.of(row));
        given(variants.findByIds(anyCollection())).willReturn(Map.of(row.catalogVariantId(),
                variant(row.catalogVariantId(), true)));
        given(products.findByIds(anyCollection())).willReturn(Map.of(PRODUCT, pikachu()));
        given(catalogImages.primaryKeysOf(anyCollection())).willReturn(Map.of());
    }

    // ---------- adding ----------

    @Nested
    class Adding {

        @Test
        @DisplayName("a card from the catalogue is added and comes back with the card attached")
        void addsAndRendersTheCard() {
            given(variants.findById(VARIANT)).willReturn(Optional.of(variant(VARIANT, true)));
            given(items.insertManual(eq(OWNER), any())).willReturn(11L);
            rowRendersAs(item(11L, VARIANT, 1, CollectionSource.MANUAL, null));

            CollectionItemResponse added = service.add(OWNER, fields(VARIANT, 1));

            assertThat(added.card().productName()).isEqualTo("Pikachu ex");
            assertThat(added.card().variantLabel()).isEqualTo("EN / FOIL / UNLIMITED");
            assertThat(added.source()).isEqualTo(CollectionSource.MANUAL);
        }

        @Test
        @DisplayName("a printing that does not exist is 404, not a foreign-key 500")
        void unknownVariantIsNotFound() {
            given(variants.findById(VARIANT)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.add(OWNER, fields(VARIANT, 1)))
                    .isInstanceOf(NotFoundException.class)
                    .extracting(e -> ((ApiException) e).errorCode())
                    .isEqualTo(ErrorCode.VARIANT_NOT_FOUND);

            verify(items, never()).insertManual(anyLong(), any());
        }

        @Test
        @DisplayName("a retired printing cannot be added as a new card")
        void retiredVariantIsRefused() {
            given(variants.findById(RETIRED_VARIANT)).willReturn(Optional.of(variant(RETIRED_VARIANT, false)));

            assertThatThrownBy(() -> service.add(OWNER, fields(RETIRED_VARIANT, 1)))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).errorCode())
                    .isEqualTo(ErrorCode.VARIANT_INACTIVE);

            verify(items, never()).insertManual(anyLong(), any());
        }

        @Test
        @DisplayName("a grade without the company that gave it is refused")
        void gradeNeedsACompany() {
            given(variants.findById(VARIANT)).willReturn(Optional.of(variant(VARIANT, true)));
            CollectionItemFields gradeOnly = new CollectionItemFields(VARIANT, CardCondition.NM, 1, null,
                    new BigDecimal("10.0"), null, null, null, null, null, false);

            assertThatThrownBy(() -> service.add(OWNER, gradeOnly))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("gradingCompany");
        }

        @Test
        @DisplayName("a certified slab is one card, so a cert number with quantity 3 is refused")
        void certNumberMeansOneCard() {
            given(variants.findById(VARIANT)).willReturn(Optional.of(variant(VARIANT, true)));
            CollectionItemFields threeSlabs = new CollectionItemFields(VARIANT, CardCondition.NM, 3, "PSA",
                    new BigDecimal("10.0"), "12345678", null, null, null, null, false);

            assertThatThrownBy(() -> service.add(OWNER, threeSlabs))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("quantity 1");
        }

        @Test
        @DisplayName("a photo key from another upload purpose is refused before storage is asked")
        void photoMustBeACollectionUpload() {
            given(variants.findById(VARIANT)).willReturn(Optional.of(variant(VARIANT, true)));

            assertThatThrownBy(() -> service.add(OWNER, withPhoto("payments/2026/09/slip.png")))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("COLLECTION_IMAGE");

            verifyNoInteractions(storage);
        }

        @Test
        @DisplayName("a photo that was never uploaded is refused")
        void photoMustExist() {
            given(variants.findById(VARIANT)).willReturn(Optional.of(variant(VARIANT, true)));
            willThrow(new NotFoundException(ErrorCode.FILE_NOT_FOUND)).given(storage).requireUploaded(PHOTO);

            assertThatThrownBy(() -> service.add(OWNER, withPhoto(PHOTO)))
                    .isInstanceOf(NotFoundException.class)
                    .extracting(e -> ((ApiException) e).errorCode())
                    .isEqualTo(ErrorCode.FILE_NOT_FOUND);
        }

        @Test
        @DisplayName("a photo already on another collector's card cannot be claimed")
        void photoOfAnotherCollectorIsRefused() {
            given(variants.findById(VARIANT)).willReturn(Optional.of(variant(VARIANT, true)));
            given(items.imageKeyUsedByAnother(PHOTO, OWNER)).willReturn(true);

            assertThatThrownBy(() -> service.add(OWNER, withPhoto(PHOTO)))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).errorCode())
                    .isEqualTo(ErrorCode.IMAGE_KEY_IN_USE);

            verify(items, never()).insertManual(anyLong(), any());
        }
    }

    // ---------- editing and removing ----------

    @Nested
    class Editing {

        @Test
        @DisplayName("another person's card reads as not found, and nothing is written")
        void anotherOwnersCardIsNotFound() {
            given(items.findOfUser(11L, STRANGER)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.update(STRANGER, 11L, fields(VARIANT, 1)))
                    .isInstanceOf(NotFoundException.class)
                    .extracting(e -> ((ApiException) e).errorCode())
                    .isEqualTo(ErrorCode.COLLECTION_ITEM_NOT_FOUND);

            verify(items, never()).update(anyLong(), anyLong(), any());
        }

        @Test
        @DisplayName("a card on a printing that was retired later can still be edited")
        void retiredButUnchangedVariantIsEditable() {
            CollectionItem kept = item(11L, RETIRED_VARIANT, 1, CollectionSource.MANUAL, null);
            rowRendersAs(kept);

            service.update(OWNER, 11L, fields(RETIRED_VARIANT, 2));

            verify(items).update(eq(11L), eq(OWNER), any());
            verify(variants, never()).findById(anyLong());
        }

        @Test
        @DisplayName("moving a card onto a retired printing is refused")
        void movingOntoRetiredVariantIsRefused() {
            given(items.findOfUser(11L, OWNER))
                    .willReturn(Optional.of(item(11L, VARIANT, 1, CollectionSource.MANUAL, null)));
            given(variants.findById(RETIRED_VARIANT)).willReturn(Optional.of(variant(RETIRED_VARIANT, false)));

            assertThatThrownBy(() -> service.update(OWNER, 11L, fields(RETIRED_VARIANT, 1)))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).errorCode())
                    .isEqualTo(ErrorCode.VARIANT_INACTIVE);
        }

        @Test
        @DisplayName("a purchased card keeps its quantity, which the table's CHECK would otherwise turn into a 500")
        void purchasedCardKeepsQuantity() {
            given(items.findOfUser(11L, OWNER))
                    .willReturn(Optional.of(item(11L, VARIANT, 1, CollectionSource.PURCHASE, null)));

            assertThatThrownBy(() -> service.update(OWNER, 11L, fields(VARIANT, 2)))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).errorCode())
                    .isEqualTo(ErrorCode.COLLECTION_ITEM_FROM_PURCHASE);

            verify(items, never()).update(anyLong(), anyLong(), any());
        }

        @Test
        @DisplayName("keeping the same photo does not ask storage again")
        void unchangedPhotoIsNotRechecked() {
            CollectionItem withExistingPhoto = item(11L, VARIANT, 1, CollectionSource.MANUAL, PHOTO);
            rowRendersAs(withExistingPhoto);

            service.update(OWNER, 11L, withPhoto(PHOTO));

            verify(storage, never()).requireUploaded(anyString());
            verify(items, never()).imageKeyUsedByAnother(anyString(), anyLong());
        }

        @Test
        @DisplayName("removing someone else's card, or one already removed, is 404")
        void removeUnknownIsNotFound() {
            given(items.softDelete(eq(11L), eq(STRANGER), any())).willReturn(false);

            assertThatThrownBy(() -> service.remove(STRANGER, 11L))
                    .isInstanceOf(NotFoundException.class)
                    .extracting(e -> ((ApiException) e).errorCode())
                    .isEqualTo(ErrorCode.COLLECTION_ITEM_NOT_FOUND);
        }
    }

    // ---------- the public page ----------

    @Nested
    class PublicPage {

        @Test
        @DisplayName("an unknown username is 404")
        void unknownUser() {
            given(users.findByUsername("nobody")).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.publicCollection("nobody", null, 0, 20))
                    .isInstanceOf(NotFoundException.class)
                    .extracting(e -> ((ApiException) e).errorCode())
                    .isEqualTo(ErrorCode.USER_NOT_FOUND);
        }

        @Test
        @DisplayName("a suspended account has no public page, and reads the same as a missing one")
        void suspendedUserIsHidden() {
            given(users.findByUsername("ploy"))
                    .willReturn(Optional.of(anActiveUser().withUsername("ploy").withStatus(UserStatus.SUSPENDED).build()));

            assertThatThrownBy(() -> service.publicCollection("ploy", null, 0, 20))
                    .isInstanceOf(NotFoundException.class);

            verifyNoInteractions(items);
        }

        @Test
        @DisplayName("the public page only ever asks for public rows, whatever the caller sends")
        void onlyPublicRowsAreRead() {
            given(users.findByUsername("ploy"))
                    .willReturn(Optional.of(anActiveUser().withId(OWNER).withUsername("ploy").build()));
            given(items.findPage(any())).willReturn(List.of());

            service.publicCollection("  PLOY ", null, 0, 20);

            ArgumentCaptor<CollectionQuery> query = ArgumentCaptor.forClass(CollectionQuery.class);
            verify(items).findPage(query.capture());
            assertThat(query.getValue().userId()).isEqualTo(OWNER);
            assertThat(query.getValue().publicItem()).isTrue();
        }

        @Test
        @DisplayName("the public shape has no field for price, date, note, cert number or source")
        void publicShapeCannotLeakPrivateFields() {
            List<String> fields = Arrays.stream(PublicCollectionItemResponse.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();

            assertThat(fields).doesNotContain(
                    "acquiredPrice", "acquiredAt", "personalNote", "certNumber", "source", "imageKey");
        }
    }

    // ---------- paging ----------

    @Test
    @DisplayName("a negative or huge page request is made safe before it reaches SQL")
    void pagingIsClamped() {
        given(items.findPage(any())).willReturn(List.of());

        service.mine(OWNER, null, null, null, -3, 5000);

        ArgumentCaptor<CollectionQuery> query = ArgumentCaptor.forClass(CollectionQuery.class);
        verify(items).findPage(query.capture());
        assertThat(query.getValue().limit()).isEqualTo(100);
        assertThat(query.getValue().offset()).isZero();
        verifyNoInteractions(variants, products, catalogImages);
    }
}
