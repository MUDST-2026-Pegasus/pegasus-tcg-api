package com.pegasus.pegasustcgapi.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.dto.CatalogImageResponse;
import com.pegasus.pegasustcgapi.dto.ProductSummaryResponse;
import com.pegasus.pegasustcgapi.exception.ApiException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.GlobalExceptionHandler;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.model.CardEdition;
import com.pegasus.pegasustcgapi.model.CardFinish;
import com.pegasus.pegasustcgapi.model.CatalogProduct;
import com.pegasus.pegasustcgapi.model.CatalogVariant;
import com.pegasus.pegasustcgapi.model.ProductType;
import com.pegasus.pegasustcgapi.service.CatalogImageService;
import com.pegasus.pegasustcgapi.service.CatalogProductService;
import com.pegasus.pegasustcgapi.service.CatalogSearchService;
import com.pegasus.pegasustcgapi.service.CatalogVariantService;
import com.pegasus.pegasustcgapi.service.ProductBrowse;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The public catalogue over HTTP [CR-2]: how query parameters reach the search, and
 * how bad parameters and unknown products come back to the caller.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CatalogProductController")
class CatalogProductControllerTest {

    private static final String PRODUCTS = ApiPaths.CATALOG + "/products";

    @Mock
    private CatalogProductService products;

    @Mock
    private CatalogVariantService variants;

    @Mock
    private CatalogImageService images;

    @Mock
    private CatalogSearchService search;

    @InjectMocks
    private CatalogProductController controller;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static CatalogProduct charizard(boolean active) {
        return new CatalogProduct(7L, (short) 1, 3, null, ProductType.SINGLE_CARD, "Charizard ex", null,
                "charizard-ex", "199/165", "SAR", null, Map.of("hp", 330), active, null,
                OffsetDateTime.now(), OffsetDateTime.now());
    }

    private static ProductSummaryResponse summary(CatalogProduct product) {
        return ProductSummaryResponse.of(product, "https://cdn.example/charizard.png", 2, null, 0);
    }

    @Test
    @DisplayName("GET /products hands name, game, type, sort and paging to the public search")
    void browsePassesEveryFilterToThePublicSearch() throws Exception {
        given(search.search(any())).willReturn(PageResponse.of(List.of(summary(charizard(true))), 1, 5, 6));

        mockMvc.perform(get(PRODUCTS)
                        .param("q", "charizard")
                        .param("gameId", "1")
                        .param("categoryId", "3")
                        .param("productType", "SINGLE_CARD")
                        .param("sort", "name")
                        .param("page", "1")
                        .param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items[0].name").value("Charizard ex"))
                .andExpect(jsonPath("$.data.items[0].rarityCode").value("SAR"))
                .andExpect(jsonPath("$.data.totalItems").value(6))
                .andExpect(jsonPath("$.data.totalPages").value(2));

        verify(search).search(argThat(browse -> browse.gameIds().equals(Set.of((short) 1))
                && Integer.valueOf(3).equals(browse.categoryId())
                && browse.cardSetId() == null
                && browse.productType() == ProductType.SINGLE_CARD
                && "charizard".equals(browse.nameQuery())
                && "name".equals(browse.sort())
                && browse.activeOnly()
                && browse.page() == 1
                && browse.size() == 5));
    }

    @Test
    @DisplayName("GET /products with no parameters is the whole active catalogue, page 0 of 20")
    void browseWithoutParametersUsesDefaults() throws Exception {
        given(search.search(any())).willReturn(PageResponse.of(List.of(), 0, 20, 0));

        mockMvc.perform(get(PRODUCTS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty());

        verify(search).search(argThat((ProductBrowse browse) -> browse.gameIds().isEmpty()
                && browse.categoryId() == null
                && browse.cardSetId() == null
                && browse.productType() == null
                && browse.nameQuery() == null
                && browse.sort() == null
                && browse.activeOnly()
                && !browse.inStockOnly()
                && browse.conditions().isEmpty()
                && browse.minPrice() == null
                && browse.maxPrice() == null
                && browse.page() == 0
                && browse.size() == 20));
    }

    @Test
    @DisplayName("attr.* filters travel to the search in the raw parameter map")
    void attributeFiltersReachTheSearch() throws Exception {
        given(search.search(any())).willReturn(PageResponse.of(List.of(), 0, 20, 0));

        mockMvc.perform(get(PRODUCTS).param("gameId", "1").param("attr.hp", "330"))
                .andExpect(status().isOk());

        verify(search).search(argThat((ProductBrowse browse) -> browse.gameIds().equals(Set.of((short) 1))
                && "330".equals(browse.rawParameters().get("attr.hp"))));
    }

    @ParameterizedTest(name = "{0}={1} -> 400 VALIDATION_FAILED naming {0}")
    @CsvSource({
            "productType, HOLOGRAPHIC",
            "gameId,      pokemon",
            "page,        first"
    })
    void unconvertibleParameterIsA400(String parameter, String value) throws Exception {
        mockMvc.perform(get(PRODUCTS).param(parameter, value))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.code").value(ErrorCode.VALIDATION_FAILED.name()))
                .andExpect(jsonPath("$.data.violations[0].field").value(parameter));

        verifyNoInteractions(search);
    }

    @Test
    @DisplayName("a refusal from the search (e.g. unknown sort) comes back as its own 400")
    void searchRefusalIsPassedThrough() throws Exception {
        given(search.search(argThat((ProductBrowse browse) -> "cheapest".equals(browse.sort()))))
                .willThrow(new ApiException(ErrorCode.VALIDATION_FAILED, "sort must be one of name, newest"));

        mockMvc.perform(get(PRODUCTS).param("sort", "cheapest"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("sort must be one of name, newest"));
    }

    @Test
    @DisplayName("GET /products/{slug} returns the card with its active printings and art")
    void productPageBySlug() throws Exception {
        CatalogProduct product = charizard(true);
        CatalogVariant variant = new CatalogVariant(70L, 7L, "SV3-199-EN", "EN", CardFinish.HOLO,
                CardEdition.NOT_APPLICABLE, null, null, null, true, OffsetDateTime.now());
        CatalogImageResponse image = new CatalogImageResponse(700L, null, "catalog/charizard.png",
                "https://cdn.example/charizard.png", "Charizard ex", (short) 0, true);

        given(products.requireActiveByIdOrSlug("charizard-ex")).willReturn(product);
        given(variants.listOfProduct(7L, false)).willReturn(List.of(variant));
        given(images.listOfProduct(7L, false)).willReturn(List.of(image));

        mockMvc.perform(get(PRODUCTS + "/charizard-ex"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.product.slug").value("charizard-ex"))
                .andExpect(jsonPath("$.data.variants[0].sku").value("SV3-199-EN"))
                .andExpect(jsonPath("$.data.images[0].primary").value(true));
    }

    @Test
    @DisplayName("GET /products/{slug} of an unknown or retired product is 404 PRODUCT_NOT_FOUND")
    void unknownProductIsNotFound() throws Exception {
        given(products.requireActiveByIdOrSlug("missingno"))
                .willThrow(new NotFoundException(ErrorCode.PRODUCT_NOT_FOUND));

        mockMvc.perform(get(PRODUCTS + "/missingno"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data.code").value(ErrorCode.PRODUCT_NOT_FOUND.name()));

        verify(variants, never()).listOfProduct(anyLong(), anyBoolean());
    }
}
