package com.pegasus.pegasustcgapi.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.dto.ProductCollectorResponse;
import com.pegasus.pegasustcgapi.exception.GlobalExceptionHandler;
import com.pegasus.pegasustcgapi.model.CatalogProduct;
import com.pegasus.pegasustcgapi.model.ProductType;
import com.pegasus.pegasustcgapi.repository.ProductCollectorRepository;
import com.pegasus.pegasustcgapi.service.CatalogProductService;
import com.pegasus.pegasustcgapi.storage.StorageService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProductCollectorController")
class ProductCollectorControllerTest {

    @Mock
    private CatalogProductService products;

    @Mock
    private ProductCollectorRepository collectors;

    @Mock
    private StorageService storage;

    @InjectMocks
    private ProductCollectorController controller;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("presigns avatar keys in collector response and preserves null/absolute urls")
    void presignsCollectorAvatarUrls() throws Exception {
        CatalogProduct product = new CatalogProduct(
                100L, (short) 1, 10, null, ProductType.SINGLE_CARD,
                "Charizard ex", null, "charizard-ex", "199/165", "SIR",
                null, Map.of(), true, null, OffsetDateTime.now(), OffsetDateTime.now());

        given(products.requireActiveByIdOrSlug("charizard-ex")).willReturn(product);

        List<ProductCollectorResponse> rawRows = List.of(
                new ProductCollectorResponse("card_kingdom_th", "Card Kingdom TH", "avatars/2026/09/ck.jpg", 3),
                new ProductCollectorResponse("no_avatar_user", "No Avatar", null, 1),
                new ProductCollectorResponse("external_user", "External Avatar", "https://cdn.example.com/ext.png", 2)
        );

        given(collectors.findByProduct(100L, 0, 20))
                .willReturn(PageResponse.of(rawRows, 0, 20, 3));

        given(storage.presignDownload("avatars/2026/09/ck.jpg"))
                .willReturn("https://storage.pegasus.test/pegasus/avatars/2026/09/ck.jpg?token=abc");

        mvc.perform(get(ApiPaths.CATALOG + "/products/charizard-ex/collectors"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalItems").value(3))
                .andExpect(jsonPath("$.data.items[0].username").value("card_kingdom_th"))
                .andExpect(jsonPath("$.data.items[0].displayName").value("Card Kingdom TH"))
                .andExpect(jsonPath("$.data.items[0].avatarUrl").value("https://storage.pegasus.test/pegasus/avatars/2026/09/ck.jpg?token=abc"))
                .andExpect(jsonPath("$.data.items[0].quantity").value(3))
                .andExpect(jsonPath("$.data.items[1].username").value("no_avatar_user"))
                .andExpect(jsonPath("$.data.items[1].avatarUrl").isEmpty())
                .andExpect(jsonPath("$.data.items[2].username").value("external_user"))
                .andExpect(jsonPath("$.data.items[2].avatarUrl").value("https://cdn.example.com/ext.png"));
    }
}
