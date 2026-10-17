package com.pegasus.pegasustcgapi.controller;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.GlobalExceptionHandler;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.service.CatalogCardEffectService;
import com.pegasus.pegasustcgapi.service.CatalogImageService;
import com.pegasus.pegasustcgapi.service.CatalogProductService;
import com.pegasus.pegasustcgapi.service.CatalogSearchService;
import com.pegasus.pegasustcgapi.service.CatalogVariantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** TCG-538: an administrator deletes a product catalogued by mistake. */
@ExtendWith(MockitoExtension.class)
class AdminCatalogProductControllerTest {

    private static final String PRODUCTS = ApiPaths.ADMIN + "/catalog/products";

    @Mock CatalogProductService products;
    @Mock CatalogVariantService variants;
    @Mock CatalogImageService images;
    @Mock CatalogSearchService search;
    @Mock CatalogCardEffectService effects;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(
                        new AdminCatalogProductController(products, variants, images, search, effects))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void deletingAnUnusedProductReturnsSuccess() throws Exception {
        mvc.perform(delete(PRODUCTS + "/501"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Product deleted"));
        verify(products).delete(501);
    }

    @Test
    void productInUseIsAConflict() throws Exception {
        doThrow(new ConflictException(ErrorCode.PRODUCT_IN_USE)).when(products).delete(501);

        mvc.perform(delete(PRODUCTS + "/501"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.code").value("PRODUCT_IN_USE"));
    }

    @Test
    void unknownProductIsNotFound() throws Exception {
        doThrow(new NotFoundException(ErrorCode.PRODUCT_NOT_FOUND)).when(products).delete(999);

        mvc.perform(delete(PRODUCTS + "/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data.code").value("PRODUCT_NOT_FOUND"));
    }
}
