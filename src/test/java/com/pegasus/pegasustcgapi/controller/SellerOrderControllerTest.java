package com.pegasus.pegasustcgapi.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.dto.SellerOrderDetailsResponse;
import com.pegasus.pegasustcgapi.dto.ShipOrderRequest;
import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.GlobalExceptionHandler;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.security.AuthClaims;
import com.pegasus.pegasustcgapi.security.AuthPrincipalArgumentResolver;
import com.pegasus.pegasustcgapi.service.OrderLifecycleService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
@DisplayName("SellerOrderController")
class SellerOrderControllerTest {

    @Mock
    private OrderLifecycleService orderLifecycleService;

    @InjectMocks
    private SellerOrderController sellerOrderController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(sellerOrderController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthPrincipalArgumentResolver())
                .build();

        setAuthenticatedUser(99L, "seller@example.com", "seller");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static void setAuthenticatedUser(long userId, String email, String username) {
        Jwt jwt = Jwt.withTokenValue("mock-access-token")
                .header("alg", "HS256")
                .subject(String.valueOf(userId))
                .claim(AuthClaims.EMAIL, email)
                .claim(AuthClaims.USERNAME, username)
                .claim(AuthClaims.ROLES, List.of("SELLER"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    private static SellerOrderDetailsResponse mockSellerOrderDetails(long id, String status) {
        return new SellerOrderDetailsResponse(
                id, 1L, 77L, "ORD-1-S77", status,
                new BigDecimal("100.00"), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("100.00"), new BigDecimal("10.00"), new BigDecimal("90.00"),
                null, null, null, null, null, null, null,
                OffsetDateTime.now(),
                List.of(), List.of(), List.of());
    }

    @Test
    @DisplayName("GET /api/v1/sellers/me/orders returns seller orders")
    void listSellerOrders_Success() throws Exception {
        given(orderLifecycleService.listSellerOrders(any())).willReturn(List.of(mockSellerOrderDetails(10L, "PAID")));

        mockMvc.perform(get(ApiPaths.SELLERS_ME_ORDERS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].id").value(10))
                .andExpect(jsonPath("$.data[0].sellerOrderNumber").value("ORD-1-S77"));
    }

    @Test
    @DisplayName("GET /api/v1/sellers/me/orders/{id} returns 404 when not found")
    void getSellerOrder_NotFound() throws Exception {
        given(orderLifecycleService.getSellerOrder(any(), eq(999L)))
                .willThrow(new NotFoundException(ErrorCode.ORDER_NOT_FOUND));

        mockMvc.perform(get(ApiPaths.SELLERS_ME_ORDERS + "/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    @DisplayName("POST /api/v1/sellers/me/orders/{id}/ship returns 200 on success")
    void shipOrder_Success() throws Exception {
        given(orderLifecycleService.shipSellerOrder(any(), eq(10L), any()))
                .willReturn(mockSellerOrderDetails(10L, "SHIPPED"));

        mockMvc.perform(post(ApiPaths.SELLERS_ME_ORDERS + "/10/ship")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"carrierName\":\"Thailand Post\",\"trackingNumber\":\"TH1234567890\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("SHIPPED"));
    }

    @Test
    @DisplayName("POST /api/v1/sellers/me/orders/{id}/ship fails validation when carrierName is blank")
    void shipOrder_ValidationFailed() throws Exception {
        mockMvc.perform(post(ApiPaths.SELLERS_ME_ORDERS + "/10/ship")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"carrierName\":\"\",\"trackingNumber\":\"TH1234567890\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }
}
