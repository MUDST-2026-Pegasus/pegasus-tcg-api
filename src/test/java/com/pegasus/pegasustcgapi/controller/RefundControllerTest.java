package com.pegasus.pegasustcgapi.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.GlobalExceptionHandler;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.security.AuthClaims;
import com.pegasus.pegasustcgapi.security.AuthPrincipalArgumentResolver;
import com.pegasus.pegasustcgapi.service.RefundService;
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

/** CR7 US-48/49: buyer refund endpoints must keep ownership and bank data scoped. */
@ExtendWith(MockitoExtension.class)
class RefundControllerTest {

    private static final String REFUNDS = "/api/v1/refunds";

    @Mock RefundService refunds;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new RefundController(refunds))
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
    void buyerHistoryUsesAuthenticatedIdentity() throws Exception {
        when(refunds.mine(73L)).thenReturn(List.of());

        mvc.perform(get(REFUNDS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
        verify(refunds).mine(73L);
    }

    @Test
    void anotherBuyersRefundIsNotDisclosed() throws Exception {
        when(refunds.mine(5L, 73L)).thenThrow(new NotFoundException(ErrorCode.REFUND_NOT_FOUND));

        mvc.perform(get(REFUNDS + "/5"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data.code").value("REFUND_NOT_FOUND"));
        verify(refunds).mine(5L, 73L);
    }

    @Test
    void destinationRequiresAllBankFieldsBeforeService() throws Exception {
        mvc.perform(put(REFUNDS + "/5/destination").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bankName\":\"Bank\",\"accountName\":\"  \",\"accountNumber\":\"1234567890\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.violations[0].field").value("accountName"));
        verifyNoInteractions(refunds);
    }

    @Test
    void destinationForWrongBuyerIsNotDisclosed() throws Exception {
        when(refunds.setDestination(5L, 73L, "Bank", "Buyer", "1234567890"))
                .thenThrow(new NotFoundException(ErrorCode.REFUND_NOT_FOUND));

        mvc.perform(put(REFUNDS + "/5/destination").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bankName\":\"Bank\",\"accountName\":\"Buyer\",\"accountNumber\":\"1234567890\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data.code").value("REFUND_NOT_FOUND"));
    }

    @Test
    void destinationForNonBankRefundIsAConflict() throws Exception {
        when(refunds.setDestination(5L, 73L, "Bank", "Buyer", "1234567890"))
                .thenThrow(new ConflictException(ErrorCode.PAYMENT_STATE));

        mvc.perform(put(REFUNDS + "/5/destination").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bankName\":\"Bank\",\"accountName\":\"Buyer\",\"accountNumber\":\"1234567890\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.code").value("PAYMENT_STATE"));
    }
}
