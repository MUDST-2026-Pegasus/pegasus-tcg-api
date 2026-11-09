package com.pegasus.pegasustcgapi.controller.pricing;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.ApiResult;
import com.pegasus.pegasustcgapi.service.pricing.MarketPricingService;
import com.pegasus.pegasustcgapi.service.pricing.MarketPricingService.RunResult;
import org.springframework.security.access.prepost.PreAuthorize;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Market pricing by hand, for the admin who cannot wait until 03:00. */
@RestController
@RequestMapping(ApiPaths.ADMIN + "/pricing")
@PreAuthorize("hasRole('ADMIN')")
public class AdminPricingController {

    private final MarketPricingService pricing;

    public AdminPricingController(MarketPricingService pricing) {
        this.pricing = pricing;
    }

    /**
     * Runs tonight's market job now. Repeating it the same day changes nothing,
     * and the second result shows that: no markets computed, nothing repriced.
     */
    @PostMapping("/market-run")
    public ApiResult<RunResult> runMarket(AuthPrincipal principal) {
        return ApiResult.success("Market run finished", pricing.runTodayFor(principal.userId()));
    }
}
