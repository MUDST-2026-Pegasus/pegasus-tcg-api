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

import com.pegasus.pegasustcgapi.exception.ApiException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.model.CardSet;
import com.pegasus.pegasustcgapi.model.CatalogCategory;
import com.pegasus.pegasustcgapi.model.CatalogProduct;
import com.pegasus.pegasustcgapi.model.ProductType;
import com.pegasus.pegasustcgapi.repository.CatalogProductRepository;
import com.pegasus.pegasustcgapi.repository.CatalogProductRepository.ProductFields;
import java.time.OffsetDateTime;
import java.util.List;
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
class CatalogProductServiceTest {

    private static final short POKEMON = 1;
    private static final short MAGIC = 2;

    @Mock
    private CatalogProductRepository products;

    @Mock
    private GameService games;

    @Mock
    private CatalogTaxonomyService taxonomy;

    private CatalogProductService service;

    @BeforeEach
    void setUp() {
        service = new CatalogProductService(products, games, taxonomy, new ProductAttributeValidator());
    }

    private static CatalogProduct pikachu() {
        return new CatalogProduct(501L, POKEMON, 201, 301, ProductType.SINGLE_CARD, "Pikachu ex",
                null, "pikachu-ex-025-187", "025/187", "RR", null, Map.of("hp", 200), true,
                9L, OffsetDateTime.now(), OffsetDateTime.now());
    }

    private static ProductFields requestFor(short gameId, int categoryId, Integer cardSetId) {
        return new ProductFields(gameId, categoryId, cardSetId, ProductType.SINGLE_CARD,
                " Pikachu ex ", null, null, "025/187", "RR", null, Map.of(), true);
    }

    private static CatalogCategory singlesOf(Short gameId) {
        return new CatalogCategory(201, gameId, null, "SINGLES", "Single cards",
                "single-cards", (short) 1, true, null);
    }

    @Test
    @DisplayName("a new product gets a slug built from its name and card number")
    void createBuildsSlug() {
        given(games.attributesOf(POKEMON)).willReturn(List.of());
        given(taxonomy.requireCategory(201)).willReturn(singlesOf(POKEMON));
        given(taxonomy.requireCardSet(301)).willReturn(
                new CardSet(301, POKEMON, "SV8A", "Terastal Festival", null, null, null, null));
        given(products.slugTaken(anyString())).willReturn(false);
        given(products.insert(any(), eq(9L))).willReturn(501L);
        given(products.findById(501L)).willReturn(Optional.of(pikachu()));

        service.create(requestFor(POKEMON, 201, 301), 9L);

        ArgumentCaptor<ProductFields> saved = ArgumentCaptor.forClass(ProductFields.class);
        verify(products).insert(saved.capture(), eq(9L));
        assertThat(saved.getValue().slug()).isEqualTo("pikachu-ex-025-187");
        assertThat(saved.getValue().name()).isEqualTo("Pikachu ex");
    }

    @Test
    @DisplayName("a category belonging to another game is refused")
    void categoryMustBelongToTheGame() {
        given(taxonomy.requireCategory(201)).willReturn(singlesOf(MAGIC));

        assertThatThrownBy(() -> service.create(requestFor(POKEMON, 201, null), 9L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("belongs to another game");

        verify(products, never()).insert(any(), any());
    }

    @Test
    @DisplayName("a cross-game category is allowed under any game")
    void crossGameCategoryIsAccepted() {
        given(games.attributesOf(POKEMON)).willReturn(List.of());
        given(taxonomy.requireCategory(203)).willReturn(singlesOf(null));
        given(products.slugTaken(anyString())).willReturn(false);
        given(products.insert(any(), eq(9L))).willReturn(501L);
        given(products.findById(501L)).willReturn(Optional.of(pikachu()));

        assertThat(service.create(requestFor(POKEMON, 203, null), 9L)).isNotNull();
    }

    @Test
    @DisplayName("a card set from another game is refused")
    void cardSetMustBelongToTheGame() {
        given(taxonomy.requireCategory(201)).willReturn(singlesOf(POKEMON));
        given(taxonomy.requireCardSet(999)).willReturn(
                new CardSet(999, MAGIC, "MH3", "Modern Horizons 3", null, null, null, null));

        assertThatThrownBy(() -> service.create(requestFor(POKEMON, 201, 999), 9L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("belongs to another game");
    }

    @Test
    @DisplayName("attributes are checked against the game's registry")
    void attributesAreValidated() {
        given(taxonomy.requireCategory(201)).willReturn(singlesOf(POKEMON));
        given(games.attributesOf(POKEMON)).willReturn(List.of());

        ProductFields withUnknownAttribute = new ProductFields(POKEMON, 201, null,
                ProductType.SINGLE_CARD, "Pikachu ex", null, null, null, null, null,
                Map.of("hp", 200), true);

        assertThatThrownBy(() -> service.create(withUnknownAttribute, 9L))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_PRODUCT_ATTRIBUTES);
    }

    @Test
    @DisplayName("editing keeps the slug and the game it was created under")
    void updateKeepsSlugAndGame() {
        given(products.findById(501L)).willReturn(Optional.of(pikachu()));
        given(taxonomy.requireCategory(202)).willReturn(
                new CatalogCategory(202, POKEMON, null, "SEALED", "Sealed", "sealed", (short) 2, true, null));
        given(games.attributesOf(POKEMON)).willReturn(List.of());

        service.update(501L, new ProductFields(MAGIC, 202, null, ProductType.BOOSTER_BOX,
                "Renamed", null, "a-new-slug", null, null, null, Map.of(), false));

        ArgumentCaptor<ProductFields> saved = ArgumentCaptor.forClass(ProductFields.class);
        verify(products).update(eq(501L), saved.capture());
        assertThat(saved.getValue().slug()).isEqualTo("pikachu-ex-025-187");
        assertThat(saved.getValue().active()).isFalse();
    }

    @Test
    @DisplayName("a product is addressable by id or by slug")
    void findsByIdOrSlug() {
        given(products.findById(501L)).willReturn(Optional.of(pikachu()));
        given(products.findBySlug("pikachu-ex-025-187")).willReturn(Optional.of(pikachu()));

        assertThat(service.requireByIdOrSlug("501").id()).isEqualTo(501L);
        assertThat(service.requireByIdOrSlug("pikachu-ex-025-187").id()).isEqualTo(501L);
    }

    @Test
    @DisplayName("an unknown slug is 404, not an empty product")
    void unknownSlugIsNotFound() {
        given(products.findBySlug("nope")).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.requireByIdOrSlug("nope"))
                .isInstanceOf(NotFoundException.class)
                .extracting(e -> ((NotFoundException) e).errorCode())
                .isEqualTo(ErrorCode.PRODUCT_NOT_FOUND);

        verify(products, never()).findById(anyLong());
    }

    @Test
    @DisplayName("a retired product is not found publicly by id or slug, but admins still read it")
    void retiredProductIsHiddenFromPublicReads() {
        CatalogProduct retired = new CatalogProduct(501L, POKEMON, 201, 301, ProductType.SINGLE_CARD,
                "Pikachu ex", null, "pikachu-ex-025-187", "025/187", "RR", null, Map.of(), false,
                9L, OffsetDateTime.now(), OffsetDateTime.now());
        given(products.findById(501L)).willReturn(Optional.of(retired));
        given(products.findBySlug("pikachu-ex-025-187")).willReturn(Optional.of(retired));

        assertThatThrownBy(() -> service.requireActiveByIdOrSlug("pikachu-ex-025-187"))
                .isInstanceOf(NotFoundException.class)
                .extracting(e -> ((NotFoundException) e).errorCode())
                .isEqualTo(ErrorCode.PRODUCT_NOT_FOUND);
        assertThatThrownBy(() -> service.requireActive(501L))
                .isInstanceOf(NotFoundException.class);

        assertThat(service.requireByIdOrSlug("pikachu-ex-025-187").active()).isFalse();
    }
}
