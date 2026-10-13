package com.pegasus.pegasustcgapi.controller;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.ApiResult;
import com.pegasus.pegasustcgapi.dto.SellerDashboardViews.Dashboard;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.service.SellerDashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The numbers on the seller's landing page. */
@Tag(name = "Seller Dashboard", description = "One shop's overview figures")
@SecurityRequirement(name = "BearerAuth")
@RestController
@RequestMapping(ApiPaths.SELLERS_ME + "/dashboard")
public class SellerDashboardController {

    private final SellerDashboardService dashboard;

    public SellerDashboardController(SellerDashboardService dashboard) {
        this.dashboard = dashboard;
    }

    @Operation(summary = "Seller dashboard",
            description = "The month's sales against the month before with a twelve-month trend, revenue, "
                    + "card cost, commission and net for the month, orders per status, active and low-stock "
                    + "listings, reviews, the wallet and the five newest orders. month is YYYY-MM in "
                    + "Bangkok time and defaults to the current month.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Dashboard retrieved"),
            @ApiResponse(responseCode = "400", description = "month is not YYYY-MM"),
            @ApiResponse(responseCode = "404", description = "The account has no seller profile")
    })
    @GetMapping
    public ApiResult<Dashboard> dashboard(
            @RequestParam(required = false) String month,
            AuthPrincipal principal) {
        return ApiResult.success(dashboard.dashboard(principal, month));
    }
}
