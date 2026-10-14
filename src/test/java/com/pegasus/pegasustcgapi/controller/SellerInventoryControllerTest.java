package com.pegasus.pegasustcgapi.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.model.CardCondition;
import com.pegasus.pegasustcgapi.model.ListingUnitStatus;
import com.pegasus.pegasustcgapi.model.MovementType;
import com.pegasus.pegasustcgapi.exception.GlobalExceptionHandler;
import com.pegasus.pegasustcgapi.security.AuthClaims;
import com.pegasus.pegasustcgapi.security.AuthPrincipalArgumentResolver;
import com.pegasus.pegasustcgapi.service.InventoryService;
import com.pegasus.pegasustcgapi.service.ListingUnitService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** CR5/RQ-7: seller stock input, loss records and inventory history over HTTP. */
@ExtendWith(MockitoExtension.class)
class SellerInventoryControllerTest {

    private static final String UNITS = ApiPaths.SELLERS_ME + "/units";

    @Mock ListingUnitService units;
    @Mock InventoryService inventory;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new SellerInventoryController(units, inventory))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthPrincipalArgumentResolver())
                .build();
        Jwt jwt = Jwt.withTokenValue("test-seller").header("alg", "HS256").subject("73")
                .claim(AuthClaims.EMAIL, "seller@example.com")
                .claim(AuthClaims.USERNAME, "seller")
                .claim(AuthClaims.ROLES, List.of("SELLER"))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void stockListIsScopedToSellerAndPassesFilters() throws Exception {
        when(units.mine(73L, 12L, ListingUnitStatus.IN_STOCK, 44L, CardCondition.NM, 1, 10))
                .thenReturn(PageResponse.of(List.of(), 1, 10, 11));

        mvc.perform(get(UNITS).param("listingId", "12").param("status", "IN_STOCK")
                        .param("variantId", "44").param("condition", "NM")
                        .param("page", "1").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalItems").value(11));
        verify(units).mine(73L, 12L, ListingUnitStatus.IN_STOCK, 44L, CardCondition.NM, 1, 10);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"catalogVariantId\":44,\"quantity\":0,\"unitCost\":100.00}",
            "{\"catalogVariantId\":44,\"quantity\":101,\"unitCost\":100.00}",
            "{\"catalogVariantId\":44,\"quantity\":1,\"unitCost\":-0.01}",
            "{\"catalogVariantId\":44,\"quantity\":1,\"unitCost\":100.001}"
    })
    void invalidStockQuantityOrCostNeverReachesInventory(String body) throws Exception {
        mvc.perform(post(UNITS).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(units);
    }

    @Test
    void stockHistoryPassesEveryFilterAndSellerIdentity() throws Exception {
        UUID uid = UUID.fromString("019f09c4-2e8a-7e5d-99b2-0443849739d3");
        when(inventory.movements(eq(73L), eq(44L), eq(CardCondition.NM), eq(MovementType.LOSS),
                eq(12L), eq(uid), eq(2), eq(5)))
                .thenReturn(PageResponse.of(List.of(), 2, 5, 0));

        mvc.perform(get(ApiPaths.SELLERS_ME + "/inventory/movements")
                        .param("variantId", "44").param("condition", "NM")
                        .param("type", "LOSS").param("listingId", "12")
                        .param("unitUid", uid.toString()).param("page", "2").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(2));
    }

    @Test
    void writeOffReasonIsTrimmedAndScopedToSeller() throws Exception {
        UUID uid = UUID.fromString("019f09c4-2e8a-7e5d-99b2-0443849739d3");

        mvc.perform(post(UNITS + "/" + uid + "/write-off").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"  damaged in storage  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Card written off"));
        verify(units).writeOff(73L, uid, "damaged in storage");
    }

    @Test
    void malformedMovementTypeIsRejectedBeforeQueryingLedger() throws Exception {
        mvc.perform(get(ApiPaths.SELLERS_ME + "/inventory/movements").param("type", "MYSTERY"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.violations[0].field").value("type"));
        verifyNoInteractions(inventory);
    }
}
