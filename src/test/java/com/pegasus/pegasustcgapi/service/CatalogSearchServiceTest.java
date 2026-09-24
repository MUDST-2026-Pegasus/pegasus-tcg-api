package com.pegasus.pegasustcgapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.dto.ProductSummaryResponse;
import com.pegasus.pegasustcgapi.dto.TrendingProductResponse;
import com.pegasus.pegasustcgapi.exception.ApiException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.model.AttributeDataType;
import com.pegasus.pegasustcgapi.model.CatalogProduct;
import com.pegasus.pegasustcgapi.model.GameAttribute;
import com.pegasus.pegasustcgapi.model.ProductType;
import com.pegasus.pegasustcgapi.repository.CatalogImageRepository;
import com.pegasus.pegasustcgapi.repository.CatalogProductRepository;
import com.pegasus.pegasustcgapi.repository.CatalogVariantRepository;
import com.pegasus.pegasustcgapi.repository.ProductMarketRepository;
import com.pegasus.pegasustcgapi.repository.ProductMarketRepository.Offer;
import com.pegasus.pegasustcgapi.repository.ProductMarketRepository.TrendingRow;
import com.pegasus.pegasustcgapi.repository.ProductSearchQuery;
import com.pegasus.pegasustcgapi.storage.StorageService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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

    private static final Instant NOW = Instant.parse("2026-09-24T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock
    private CatalogProductRepository products;

    @Mock
    private CatalogVariantRepository variants;

    @Mock
    private CatalogImageRepository images;

    @Mock
    private ProductMarketRepository market;

    @Mock
    private GameService games;

    @Mock
    private StorageService storage;

    private CatalogSearchService service;

    @BeforeEach
    void setUp() {
        service = new CatalogSearchService(products, variants, images, market, games, storage, CLOCK);
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

        return service.search(gameId, null, null, null, null, parameters, sort, true, false, page, size);
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

        service.search(POKEMON, null, null, null, null, Map.of(), null, false, false, 0, 20);

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

    @Test
    @DisplayName("a tile carries the cheapest price on sale and how many listings there are")
    void summariesCarryLowestPrice() {
        given(products.search(any())).willReturn(List.of(pikachu()));
        given(products.count(any())).willReturn(1L);
        given(market.offersOf(anyCollection())).willReturn(Map.of(501L, new Offer(new BigDecimal("150.00"), 3)));

        PageResponse<ProductSummaryResponse> page = searchWith(POKEMON, Map.of(), null, 0, 20);

        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.lowestPrice()).isEqualByComparingTo("150");
            assertThat(item.listingCount()).isEqualTo(3);
        });
    }

    @Test
    @DisplayName("a card nobody is selling has no price rather than a made-up one")
    void unsoldProductHasNoPrice() {
        given(products.search(any())).willReturn(List.of(pikachu()));
        given(products.count(any())).willReturn(1L);

        PageResponse<ProductSummaryResponse> page = searchWith(POKEMON, Map.of(), null, 0, 20);

        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.lowestPrice()).isNull();
            assertThat(item.listingCount()).isZero();
        });
    }

    @Test
    @DisplayName("asking for stock only reaches the query")
    void inStockReachesTheQuery() {
        given(products.search(any())).willReturn(List.of());

        service.search(POKEMON, null, null, null, null, Map.of(), null, true, true, 0, 20);

        assertThat(capturedQuery().inStockOnly()).isTrue();
    }

    @Test
    @DisplayName("trending keeps the market's order and numbers the places from 1")
    void trendingKeepsRankOrder() {
        CatalogProduct pikachu = pikachu();
        CatalogProduct mew = new CatalogProduct(502L, POKEMON, 201, 301, ProductType.SINGLE_CARD, "Mew ex",
                null, "mew-ex-151-165", "151/165", "SR", null, Map.of(), true,
                9L, OffsetDateTime.now(), OffsetDateTime.now());
        given(market.trending(eq(POKEMON), eq(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).minusDays(30)), eq(10)))
                .willReturn(List.of(new TrendingRow(502L, 7), new TrendingRow(501L, 2)));
        given(products.findByIds(anyCollection())).willReturn(Map.of(501L, pikachu, 502L, mew));

        List<TrendingProductResponse> rail = service.trending(POKEMON, null, null);

        assertThat(rail).extracting(TrendingProductResponse::rank).containsExactly(1, 2);
        assertThat(rail).extracting(entry -> entry.product().slug())
                .containsExactly("mew-ex-151-165", "pikachu-ex-025-187");
        assertThat(rail.getFirst().unitsSold()).isEqualTo(7);
    }

    @Test
    @DisplayName("a trending rail longer than 50 places is refused")
    void trendingLimitIsCapped() {
        assertThatThrownBy(() -> service.trending(null, 30, 51))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        verifyNoInteractions(market);
    }
}
