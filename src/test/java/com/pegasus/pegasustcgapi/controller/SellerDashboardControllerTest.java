package com.pegasus.pegasustcgapi.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.dto.SellerDashboardViews.Dashboard;
import com.pegasus.pegasustcgapi.dto.SellerDashboardViews.Listings;
import com.pegasus.pegasustcgapi.dto.SellerDashboardViews.MonthSummary;
import com.pegasus.pegasustcgapi.dto.SellerDashboardViews.MonthlySales;
import com.pegasus.pegasustcgapi.dto.SellerDashboardViews.Period;
import com.pegasus.pegasustcgapi.dto.SellerDashboardViews.Reviews;
import com.pegasus.pegasustcgapi.dto.SellerDashboardViews.Sales;
import com.pegasus.pegasustcgapi.dto.SellerDashboardViews.Wallet;
import com.pegasus.pegasustcgapi.exception.BadRequestException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.GlobalExceptionHandler;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.security.AuthClaims;
import com.pegasus.pegasustcgapi.security.AuthPrincipalArgumentResolver;
import com.pegasus.pegasustcgapi.service.SellerDashboardService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
@DisplayName("SellerDashboardController")
class SellerDashboardControllerTest {

    @Mock
    private SellerDashboardService dashboard;

    @InjectMocks
    private SellerDashboardController controller;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthPrincipalArgumentResolver())
                .build();

        Jwt jwt = Jwt.withTokenValue("mock-access-token")
                .header("alg", "HS256")
                .subject("99")
                .claim(AuthClaims.EMAIL, "seller@example.com")
                .claim(AuthClaims.USERNAME, "seller")
                .claim(AuthClaims.ROLES, List.of("SELLER"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static Dashboard sample() {
        OffsetDateTime from = OffsetDateTime.parse("2026-10-01T00:00:00+07:00");
        return new Dashboard(
                new Period("2026-10", from, from.plusMonths(1)),
                new Sales(new BigDecimal("24850.00"), new BigDecimal("22187.50"), 56, 50,
                        List.of(new MonthlySales("2026-10", new BigDecimal("24850.00")))),
                new MonthSummary(new BigDecimal("26000.00"), new BigDecimal("16420.00"),
                        new BigDecimal("1242.50"), new BigDecimal("8337.50")),
                Map.of("PAID", 5L),
                new Listings(142, 5, 2, 0, List.of()),
                new Reviews(new BigDecimal("4.8"), 312, 2, 0),
                new Wallet(new BigDecimal("3420.00"), new BigDecimal("1850.50")),
                List.of());
    }

    @Test
    @DisplayName("GET /sellers/me/dashboard returns the current month by default")
    void dashboard_Success() throws Exception {
        given(dashboard.dashboard(any(), isNull())).willReturn(sample());

        mockMvc.perform(get(ApiPaths.SELLERS_ME + "/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.period.month").value("2026-10"))
                .andExpect(jsonPath("$.data.sales.value").value(24850.00))
                .andExpect(jsonPath("$.data.month.net").value(8337.50))
                .andExpect(jsonPath("$.data.orders.PAID").value(5))
                .andExpect(jsonPath("$.data.listings.lowStockThreshold").value(2))
                .andExpect(jsonPath("$.data.wallet.withdrawable").value(3420.00));
    }

    @Test
    @DisplayName("GET /sellers/me/dashboard?month= passes the month through")
    void dashboard_Month() throws Exception {
        given(dashboard.dashboard(any(), eq("2026-08"))).willReturn(sample());

        mockMvc.perform(get(ApiPaths.SELLERS_ME + "/dashboard").param("month", "2026-08"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /sellers/me/dashboard is a 400 for a malformed month")
    void dashboard_BadMonth() throws Exception {
        given(dashboard.dashboard(any(), eq("October")))
                .willThrow(new BadRequestException(ErrorCode.VALIDATION_FAILED, "month must look like 2026-10"));

        mockMvc.perform(get(ApiPaths.SELLERS_ME + "/dashboard").param("month", "October"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /sellers/me/dashboard is a 404 without a seller profile")
    void dashboard_NoSellerProfile() throws Exception {
        given(dashboard.dashboard(any(), isNull())).willThrow(new NotFoundException(ErrorCode.SELLER_NOT_FOUND));

        mockMvc.perform(get(ApiPaths.SELLERS_ME + "/dashboard"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data.code").value("SELLER_NOT_FOUND"));
    }
}
