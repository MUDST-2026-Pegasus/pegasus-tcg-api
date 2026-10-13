package com.pegasus.pegasustcgapi.controller;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.ApiResult;
import com.pegasus.pegasustcgapi.dto.AdminMetricsViews.Overview;
import com.pegasus.pegasustcgapi.service.AdminMetricsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The numbers on the admin landing page. */
@Tag(name = "Metrics (Admin)", description = "Platform overview figures")
@SecurityRequirement(name = "BearerAuth")
@RestController
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping(ApiPaths.ADMIN + "/metrics")
public class AdminMetricsController {

    private final AdminMetricsService metrics;

    public AdminMetricsController(AdminMetricsService metrics) {
        this.metrics = metrics;
    }

    @Operation(summary = "Platform overview",
            description = "Sales, new sellers, active listings and commission for one month against "
                    + "the month before, with eight weekly points each, the six-month singles/sealed "
                    + "trend, the top five sellers and sales by game. month is YYYY-MM in Bangkok "
                    + "time and defaults to the current month.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Overview retrieved"),
            @ApiResponse(responseCode = "400", description = "month is not YYYY-MM")
    })
    @GetMapping("/overview")
    public ApiResult<Overview> overview(@RequestParam(required = false) String month) {
        return ApiResult.success(metrics.overview(month));
    }
}
