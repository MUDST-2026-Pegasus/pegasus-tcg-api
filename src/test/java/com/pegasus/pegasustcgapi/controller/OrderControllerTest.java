package com.pegasus.pegasustcgapi.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.dto.CancelOrderRequest;
import com.pegasus.pegasustcgapi.dto.OrderDetailsResponse;
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
@DisplayName("OrderController")
class OrderControllerTest {

    @Mock
    private OrderLifecycleService orderLifecycleService;

    @InjectMocks
    private OrderController orderController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(orderController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthPrincipalArgumentResolver())
                .build();

        setAuthenticatedUser(42L, "buyer@example.com", "buyer");
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
                .claim(AuthClaims.ROLES, List.of("BUYER"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    private static OrderDetailsResponse mockOrderDetails(long orderId, String status) {
        return new OrderDetailsResponse(
                orderId,
                "ORD-" + orderId,
                42L,
                status,
                "THB",
                new BigDecimal("100.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("100.00"),
                1L,
                "{}",
                "Please deliver carefully",
                OffsetDateTime.now(),
                null, null, null,
                List.of());
    }

    @Test
    @DisplayName("GET /api/v1/orders returns list of orders")
    void listOrders_Success() throws Exception {
        given(orderLifecycleService.listBuyerOrders(any(), anyInt(), anyInt()))
                .willReturn(PageResponse.of(List.of(mockOrderDetails(1L, "PENDING_PAYMENT")), 0, 20, 1));

        mockMvc.perform(get(ApiPaths.ORDERS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.totalItems").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(1))
                .andExpect(jsonPath("$.data.items[0].orderNumber").value("ORD-1"));
    }

    @Test
    @DisplayName("GET /api/v1/orders/{id} returns 404 if order not found")
    void getOrder_NotFound() throws Exception {
        given(orderLifecycleService.getBuyerOrder(any(), eq(999L)))
                .willThrow(new NotFoundException(ErrorCode.ORDER_NOT_FOUND));

        mockMvc.perform(get(ApiPaths.ORDERS + "/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    @DisplayName("POST /api/v1/orders/{id}/cancel returns 200 on successful cancellation")
    void cancelOrder_Success() throws Exception {
        given(orderLifecycleService.cancelBuyerOrder(any(), eq(1L), any()))
                .willReturn(mockOrderDetails(1L, "CANCELLED"));

        mockMvc.perform(post(ApiPaths.ORDERS + "/1/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Changed mind\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
    }

    @Test
    @DisplayName("POST /api/v1/orders/{id}/cancel returns 409 if cancel window closed")
    void cancelOrder_WindowClosed() throws Exception {
        given(orderLifecycleService.cancelBuyerOrder(any(), eq(1L), any()))
                .willThrow(new ConflictException(ErrorCode.CANCEL_WINDOW_CLOSED));

        mockMvc.perform(post(ApiPaths.ORDERS + "/1/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Late cancellation\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.code").value("CANCEL_WINDOW_CLOSED"));
    }

    @Test
    @DisplayName("POST /api/v1/orders/{id}/confirm-received returns 200 on success")
    void confirmReceived_Success() throws Exception {
        given(orderLifecycleService.confirmBuyerOrderReceived(any(), eq(1L)))
                .willReturn(mockOrderDetails(1L, "COMPLETED"));

        mockMvc.perform(post(ApiPaths.ORDERS + "/1/confirm-received"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));
    }
}
