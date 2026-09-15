package com.pegasus.pegasustcgapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.model.CardEdition;
import com.pegasus.pegasustcgapi.model.CardFinish;
import com.pegasus.pegasustcgapi.model.CardSet;
import com.pegasus.pegasustcgapi.model.CatalogProduct;
import com.pegasus.pegasustcgapi.model.CatalogVariant;
import com.pegasus.pegasustcgapi.model.Game;
import com.pegasus.pegasustcgapi.model.ProductType;
import com.pegasus.pegasustcgapi.repository.CatalogVariantRepository;
import com.pegasus.pegasustcgapi.repository.CatalogVariantRepository.VariantFields;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CatalogVariantServiceTest {

    private static final short POKEMON = 1;
    private static final long PRODUCT_ID = 501L;

    @Mock
    private CatalogVariantRepository variants;

    @Mock
    private CatalogProductService products;

    @Mock
    private GameService games;

    @Mock
    private CatalogTaxonomyService taxonomy;

    private CatalogVariantService service;

    @BeforeEach
    void setUp() {
        service = new CatalogVariantService(variants, products, games, taxonomy);
    }

    private static CatalogProduct pikachu() {
        return new CatalogProduct(PRODUCT_ID, POKEMON, 201, 301, ProductType.SINGLE_CARD,
                "Pikachu ex", null, "pikachu-ex-025-187", "025/187", "RR", null, Map.of(), true,
                9L, OffsetDateTime.now(), OffsetDateTime.now());
    }

    private static CatalogVariant englishNormal() {
        return new CatalogVariant(901L, PRODUCT_ID, "POKEMON-SV8A-025-187-EN-NORMAL", "EN",
                CardFinish.NORMAL, CardEdition.UNLIMITED, null, null, null, true, OffsetDateTime.now());
    }

    private static VariantFields fields(String sku, String language, CardFinish finish) {
        return new VariantFields(sku, language, finish, CardEdition.UNLIMITED, null, null, null, true);
    }

    @Test
    @DisplayName("a SKU left out is built from game, set, card number, language and finish")
    void generatesSkuFromTheCard() {
        given(products.require(PRODUCT_ID)).willReturn(pikachu());
        given(games.require(POKEMON)).willReturn(new Game(POKEMON, "POKEMON", "Pokemon TCG", null,
                "pokemon-tcg", null, (short) 1, true, null, OffsetDateTime.now()));
        given(taxonomy.requireCardSet(301)).willReturn(
                new CardSet(301, POKEMON, "SV8A", "Terastal Festival", null, null, null, null));
        given(variants.identityTaken(anyLong(), anyString(), any(), any(), any(), any()))
                .willReturn(false);
        given(variants.skuTaken(anyString(), eq(null))).willReturn(false);
        given(variants.insert(eq(PRODUCT_ID), any())).willReturn(901L);
        given(variants.findById(901L)).willReturn(Optional.of(englishNormal()));

        service.create(PRODUCT_ID, fields(null, "en", CardFinish.NORMAL));

        ArgumentCaptor<VariantFields> saved = ArgumentCaptor.forClass(VariantFields.class);
        verify(variants).insert(eq(PRODUCT_ID), saved.capture());
        assertThat(saved.getValue().sku()).isEqualTo("POKEMON-SV8A-025-187-EN-NORMAL-UNLIMITED");
        assertThat(saved.getValue().languageCode()).isEqualTo("EN");
    }

    @Test
    @DisplayName("a SKU that was supplied is folded and must be free")
    void suppliedSkuMustBeFree() {
        given(products.require(PRODUCT_ID)).willReturn(pikachu());
        given(variants.identityTaken(anyLong(), anyString(), any(), any(), any(), any()))
                .willReturn(false);
        given(variants.skuTaken("MY-SKU-1", null)).willReturn(true);

        assertThatThrownBy(() -> service.create(PRODUCT_ID, fields("my-sku-1", "EN", CardFinish.NORMAL)))
                .isInstanceOf(ConflictException.class)
                .extracting(e -> ((ConflictException) e).errorCode())
                .isEqualTo(ErrorCode.SKU_ALREADY_USED);

        verify(variants, never()).insert(anyLong(), any());
    }

    @Test
    @DisplayName("the same printing cannot be catalogued twice")
    void identityIsUniquePerProduct() {
        given(products.require(PRODUCT_ID)).willReturn(pikachu());
        given(variants.identityTaken(PRODUCT_ID, "EN", CardFinish.NORMAL, CardEdition.UNLIMITED,
                null, null)).willReturn(true);

        assertThatThrownBy(() -> service.create(PRODUCT_ID, fields(null, "EN", CardFinish.NORMAL)))
                .isInstanceOf(ConflictException.class)
                .extracting(e -> ((ConflictException) e).errorCode())
                .isEqualTo(ErrorCode.VARIANT_ALREADY_EXISTS);

        verify(variants, never()).insert(anyLong(), any());
    }

    @Test
    @DisplayName("a different finish of the same card is a different variant, not a clash")
    void differentFinishIsAllowed() {
        given(products.require(PRODUCT_ID)).willReturn(pikachu());
        given(variants.identityTaken(PRODUCT_ID, "EN", CardFinish.FOIL, CardEdition.UNLIMITED,
                null, null)).willReturn(false);
        given(variants.skuTaken("MY-FOIL", null)).willReturn(false);
        given(variants.insert(eq(PRODUCT_ID), any())).willReturn(902L);
        given(variants.findById(902L)).willReturn(Optional.of(englishNormal()));

        assertThat(service.create(PRODUCT_ID, fields("MY-FOIL", "EN", CardFinish.FOIL))).isNotNull();
    }

    @Test
    @DisplayName("editing without a SKU keeps the one it already had")
    void updateKeepsExistingSku() {
        given(variants.findById(901L)).willReturn(Optional.of(englishNormal()));
        given(variants.identityTaken(PRODUCT_ID, "EN", CardFinish.NORMAL, CardEdition.UNLIMITED,
                null, 901L)).willReturn(false);

        service.update(PRODUCT_ID, 901L, fields(null, "EN", CardFinish.NORMAL));

        ArgumentCaptor<VariantFields> saved = ArgumentCaptor.forClass(VariantFields.class);
        verify(variants).update(eq(901L), saved.capture());
        assertThat(saved.getValue().sku()).isEqualTo("POKEMON-SV8A-025-187-EN-NORMAL");
    }

    @Test
    @DisplayName("a SKU or barcode resolves to the printing, with the card attached")
    void lookupByCodeCarriesTheCard() {
        given(variants.findByCode("POKEMON-SV8A-025-187-EN-NORMAL"))
                .willReturn(Optional.of(englishNormal()));
        given(products.require(PRODUCT_ID)).willReturn(pikachu());

        var found = service.lookupByCode("  POKEMON-SV8A-025-187-EN-NORMAL  ");

        assertThat(found.productName()).isEqualTo("Pikachu ex");
        assertThat(found.productSlug()).isEqualTo("pikachu-ex-025-187");
        assertThat(found.variantLabel()).isEqualTo("EN / NORMAL / UNLIMITED");
    }

    @Test
    @DisplayName("a code nothing carries is 404, with the code echoed back")
    void lookupByUnknownCode() {
        given(variants.findByCode("NOPE")).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.lookupByCode("NOPE"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("NOPE");
    }

    @Test
    @DisplayName("a variant of another product is not found through this one")
    void variantOfAnotherProductIsNotFound() {
        CatalogVariant otherCard = new CatalogVariant(902L, 777L, "OTHER", "EN", CardFinish.NORMAL,
                CardEdition.UNLIMITED, null, null, null, true, OffsetDateTime.now());

        given(variants.findById(902L)).willReturn(Optional.of(otherCard));

        assertThatThrownBy(() -> service.requireOfProduct(PRODUCT_ID, 902L))
                .isInstanceOf(NotFoundException.class)
                .extracting(e -> ((NotFoundException) e).errorCode())
                .isEqualTo(ErrorCode.VARIANT_NOT_FOUND);
    }

    @Test
    @DisplayName("a retired printing is not found publicly")
    void retiredVariantIsHiddenFromPublicReads() {
        CatalogVariant retired = new CatalogVariant(901L, PRODUCT_ID, "POKEMON-SV8A-025-187-EN-NORMAL", "EN",
                CardFinish.NORMAL, CardEdition.UNLIMITED, null, null, null, false, OffsetDateTime.now());
        given(variants.findById(901L)).willReturn(Optional.of(retired));

        assertThatThrownBy(() -> service.requireActive(901L))
                .isInstanceOf(NotFoundException.class)
                .extracting(e -> ((NotFoundException) e).errorCode())
                .isEqualTo(ErrorCode.VARIANT_NOT_FOUND);
    }

    @Test
    @DisplayName("a live printing of a retired product is not found publicly either")
    void variantOfRetiredProductIsHiddenFromPublicReads() {
        CatalogProduct retiredProduct = new CatalogProduct(PRODUCT_ID, POKEMON, 201, 301, ProductType.SINGLE_CARD,
                "Pikachu ex", null, "pikachu-ex-025-187", "025/187", "RR", null, Map.of(), false,
                9L, OffsetDateTime.now(), OffsetDateTime.now());
        given(variants.findById(901L)).willReturn(Optional.of(englishNormal()));
        given(products.require(PRODUCT_ID)).willReturn(retiredProduct);

        assertThatThrownBy(() -> service.requireActive(901L))
                .isInstanceOf(NotFoundException.class)
                .extracting(e -> ((NotFoundException) e).errorCode())
                .isEqualTo(ErrorCode.VARIANT_NOT_FOUND);
    }

    @Test
    @DisplayName("the public list of printings needs a live product; the admin list does not")
    void publicListNeedsALiveProduct() {
        service.listOfProduct(PRODUCT_ID, false);
        service.listOfProduct(PRODUCT_ID, true);

        verify(products).requireActive(PRODUCT_ID);
        verify(products).require(PRODUCT_ID);
    }
}
