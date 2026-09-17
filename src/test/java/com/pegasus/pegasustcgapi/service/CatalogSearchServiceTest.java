package com.pegasus.pegasustcgapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.dto.ProductSummaryResponse;
import com.pegasus.pegasustcgapi.exception.ApiException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.model.AttributeDataType;
import com.pegasus.pegasustcgapi.model.CatalogProduct;
import com.pegasus.pegasustcgapi.model.GameAttribute;
import com.pegasus.pegasustcgapi.model.ProductType;
import com.pegasus.pegasustcgapi.repository.CatalogImageRepository;
import com.pegasus.pegasustcgapi.repository.CatalogProductRepository;
import com.pegasus.pegasustcgapi.repository.CatalogVariantRepository;
import com.pegasus.pegasustcgapi.repository.ProductSearchQuery;
import com.pegasus.pegasustcgapi.storage.StorageService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CatalogSearchServiceTest {

    private static final short POKEMON = 1;

    @Mock
    private CatalogProductRepository products;

    @Mock
    private CatalogVariantRepository variants;

    @Mock
    private CatalogImageRepository images;

    @Mock
    private GameService games;

    @Mock
    private StorageService storage;

    private CatalogSearchService service;

    @BeforeEach
    void setUp() {
        service = new CatalogSearchService(products, variants, images, games, storage);
    }

    private static GameAttribute attribute(String key, AttributeDataType type, String... options) {
        return new GameAttribute(1, POKEMON, key, key, type, List.of(options), true, false, (short) 0);
    }

    private static final List<GameAttribute> REGISTRY = List.of(
            attribute("hp", AttributeDataType.NUMBER),
            attribute("card_type", AttributeDataType.ENUM, "Lightning", "Fire"),
            attribute("is_promo", AttributeDataType.BOOLEAN),
            attribute("printed_on", AttributeDataType.DATE));

    private static CatalogProduct pikachu() {
        return new CatalogProduct(501L, POKEMON, 201, 301, ProductType.SINGLE_CARD, "Pikachu ex",
                null, "pikachu-ex-025-187", "025/187", "RR", null, Map.of("hp", 200), true,
                9L, OffsetDateTime.now(), OffsetDateTime.now());
    }

    private PageResponse<ProductSummaryResponse> searchWith(
            Short gameId, Map<String, String> parameters, String sort, int page, int size) {

        return service.search(gameId, null, null, null, null, parameters, sort, true, page, size);
    }

    private ProductSearchQuery capturedQuery() {
        ArgumentCaptor<ProductSearchQuery> query = ArgumentCaptor.forClass(ProductSearchQuery.class);
        verify(products).search(query.capture());
        return query.getValue();
    }

    @Test
    @DisplayName("a number filter travels as a number, or jsonb containment matches nothing")
    void numericAttributeIsTypedAsANumber() {
        given(games.attributesOf(POKEMON)).willReturn(REGISTRY);
        given(products.search(any())).willReturn(List.of());

        searchWith(POKEMON, Map.of("attr.hp", "200"), null, 0, 20);

        assertThat(capturedQuery().attributes()).containsEntry("hp", 200L);
    }

    @Test
    @DisplayName("each declared type is converted to its own JSON type")
    void everyTypeIsConverted() {
        given(games.attributesOf(POKEMON)).willReturn(REGISTRY);
        given(products.search(any())).willReturn(List.of());

        searchWith(POKEMON, Map.of(
                "attr.card_type", "Lightning",
                "attr.is_promo", "true",
                "attr.printed_on", "2024-10-18"), null, 0, 20);

        assertThat(capturedQuery().attributes())
                .containsEntry("card_type", "Lightning")
                .containsEntry("is_promo", true)
                .containsEntry("printed_on", "2024-10-18");
    }

    @Test
    @DisplayName("a mistyped filter key is refused, not silently dropped")
    void unknownAttributeKeyIsRejected() {
        given(games.attributesOf(POKEMON)).willReturn(REGISTRY);

        assertThatThrownBy(() -> searchWith(POKEMON, Map.of("attr.hpp", "200"), null, 0, 20))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_PRODUCT_ATTRIBUTES);

        verifyNoInteractions(products);
    }

    @Test
    @DisplayName("a value that cannot be its declared type is refused")
    void badValueIsRejected() {
        given(games.attributesOf(POKEMON)).willReturn(REGISTRY);

        assertThatThrownBy(() -> searchWith(POKEMON, Map.of("attr.hp", "lots"), null, 0, 20))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("hp must be a number");

        assertThatThrownBy(() -> searchWith(POKEMON, Map.of("attr.card_type", "Psychic"), null, 0, 20))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("card_type must be one of");
    }

    @Test
    @DisplayName("attribute filters without a game cannot be typed, so they are refused")
    void attributeFilterNeedsAGame() {
        assertThatThrownBy(() -> searchWith(null, Map.of("attr.hp", "200"), null, 0, 20))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("needs a gameId");

        verifyNoInteractions(games);
    }

    @Test
    @DisplayName("parameters that are not filters are ignored")
    void nonAttributeParametersAreIgnored() {
        given(products.search(any())).willReturn(List.of());

        searchWith(POKEMON, Map.of("gameId", "1", "page", "0", "attr.", "  "), null, 0, 20);

        assertThat(capturedQuery().attributes()).isEmpty();
        verifyNoInteractions(games);
    }

    @Test
    @DisplayName("page size is capped, so a client cannot ask for the whole catalogue")
    void pageSizeIsCapped() {
        given(products.search(any())).willReturn(List.of());

        searchWith(POKEMON, Map.of(), null, 3, 5000);

        assertThat(capturedQuery().limit()).isEqualTo(100);
        assertThat(capturedQuery().offset()).isEqualTo(300);
    }

    @Test
    @DisplayName("an unknown sort is an error, not a silently different order")
    void unknownSortIsRejected() {
        assertThatThrownBy(() -> searchWith(POKEMON, Map.of(), "price", 0, 20))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("sort must be one of");
    }

    @Test
    @DisplayName("sort names are accepted however they are cased")
    void sortIsCaseInsensitive() {
        given(products.search(any())).willReturn(List.of());

        searchWith(POKEMON, Map.of(), "Newest", 0, 20);

        assertThat(capturedQuery().sort()).isEqualTo(ProductSearchQuery.Sort.NEWEST);
    }

    @Test
    @DisplayName("a page carries its primary image and how many printings exist")
    void summariesCarryImageAndVariantCount() {
        given(products.search(any())).willReturn(List.of(pikachu()));
        given(products.count(any())).willReturn(1L);
        given(images.primaryKeysOf(anyCollection())).willReturn(Map.of(501L, "catalog/a.png"));
        given(variants.activeCountsOf(anyCollection())).willReturn(Map.of(501L, 2));
        given(storage.presignDownload("catalog/a.png")).willReturn("http://localhost:9000/signed");

        PageResponse<ProductSummaryResponse> page = searchWith(POKEMON, Map.of(), null, 0, 20);

        assertThat(page.totalItems()).isEqualTo(1);
        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.primaryImageUrl()).isEqualTo("http://localhost:9000/signed");
            assertThat(item.variantCount()).isEqualTo(2);
        });
    }

    @Test
    @DisplayName("an admin browse asks for the deactivated rows too")
    void adminSeesInactiveProducts() {
        given(products.search(any())).willReturn(List.of());

        service.search(POKEMON, null, null, null, null, Map.of(), null, false, 0, 20);

        assertThat(capturedQuery().activeOnly()).isFalse();
    }

    @Test
    @DisplayName("the public browse only ever sees what is active")
    void publicBrowseIsActiveOnly() {
        given(products.search(any())).willReturn(List.of());

        searchWith(POKEMON, Map.of(), null, 0, 20);

        assertThat(capturedQuery().activeOnly()).isTrue();
    }

    @Test
    @DisplayName("a product with no art is a tile without a picture, not an error")
    void productWithoutImages() {
        given(products.search(any())).willReturn(List.of(pikachu()));
        given(products.count(any())).willReturn(1L);
        given(images.primaryKeysOf(anyCollection())).willReturn(Map.of());
        given(variants.activeCountsOf(anyCollection())).willReturn(Map.of());

        PageResponse<ProductSummaryResponse> page = searchWith(POKEMON, Map.of(), null, 0, 20);

        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.primaryImageUrl()).isNull();
            assertThat(item.variantCount()).isZero();
        });
        verifyNoInteractions(storage);
    }
}
