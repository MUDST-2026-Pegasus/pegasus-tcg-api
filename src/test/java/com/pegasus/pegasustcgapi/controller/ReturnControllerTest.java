package com.pegasus.pegasustcgapi.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.GlobalExceptionHandler;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.security.AuthClaims;
import com.pegasus.pegasustcgapi.security.AuthPrincipalArgumentResolver;
import com.pegasus.pegasustcgapi.service.ReturnService;
import com.pegasus.pegasustcgapi.service.ReturnService.EvidenceLink;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

/** CR7 US-48/49: return and dispute requests over HTTP. */
@ExtendWith(MockitoExtension.class)
class ReturnControllerTest {

    private static final String BUYER_RETURNS = ApiPaths.ORDERS + "/12/returns";
    private static final String SELLER_RETURNS = ApiPaths.SELLERS_ME + "/returns";

    @Mock ReturnService returns;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ReturnController(returns, new ObjectMapper()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthPrincipalArgumentResolver())
                .build();
        Jwt jwt = Jwt.withTokenValue("test-buyer").header("alg", "HS256").subject("73")
                .claim(AuthClaims.EMAIL, "buyer@example.com")
                .claim(AuthClaims.USERNAME, "buyer")
                .claim(AuthClaims.ROLES, List.of("BUYER"))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void returnRequestNeedsAtLeastOneItem() throws Exception {
        mvc.perform(post(BUYER_RETURNS).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasonCode\":\"DAMAGED\",\"evidenceKeys\":[],\"items\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.violations[0].field").value("items"));
        verifyNoInteractions(returns);
    }

    @Test
    void returnRequestNeedsExplicitEvidenceList() throws Exception {
        mvc.perform(post(BUYER_RETURNS).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasonCode\":\"DAMAGED\",\"items\":[{\"orderItemId\":4,\"quantity\":1}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.violations[0].field").value("evidenceKeys"));
        verifyNoInteractions(returns);
    }

    @Test
    void shipmentNeedsTrackingNumber() throws Exception {
        mvc.perform(post(BUYER_RETURNS + "/5/ship").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"trackingNumber\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.violations[0].field").value("trackingNumber"));
        verifyNoInteractions(returns);
    }

    @Test
    void refundApprovalRejectsZeroAmount() throws Exception {
        mvc.perform(post(SELLER_RETURNS + "/5/approve").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resolution\":\"FULL_REFUND\",\"refundAmount\":0,\"requireShipBack\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.violations[0].field").value("refundAmount"));
        verifyNoInteractions(returns);
    }

    @Test
    void refundApprovalRejectsSubCentPrecision() throws Exception {
        mvc.perform(post(SELLER_RETURNS + "/5/approve").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resolution\":\"FULL_REFUND\",\"refundAmount\":12.345,\"requireShipBack\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.violations[0].field").value("refundAmount"));
        verifyNoInteractions(returns);
    }

    @Test
    void refundApprovalNeedsShippingDecision() throws Exception {
        mvc.perform(post(SELLER_RETURNS + "/5/approve").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resolution\":\"FULL_REFUND\",\"refundAmount\":12.34}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.violations[0].field").value("requireShipBack"));
        verifyNoInteractions(returns);
    }

    @Test
    void evidenceRequestUsesCurrentSellerIdentity() throws Exception {
        when(returns.sellerEvidence(73L, 5L)).thenReturn(List.of(new EvidenceLink(1, "https://example.test/photo")));

        mvc.perform(get(SELLER_RETURNS + "/5/evidence"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].url").value("https://example.test/photo"));
        verify(returns).sellerEvidence(73L, 5L);
    }

    @Test
    void evidenceForAnotherSellersReturnIsHiddenAsNotFound() throws Exception {
        when(returns.sellerEvidence(73L, 5L)).thenThrow(new NotFoundException(ErrorCode.RETURN_NOT_FOUND));

        mvc.perform(get(SELLER_RETURNS + "/5/evidence"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data.code").value("RETURN_NOT_FOUND"));
    }
}
