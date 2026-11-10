package com.pegasus.pegasustcgapi.controller.listing;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import com.pegasus.pegasustcgapi.common.ApiResult;
import com.pegasus.pegasustcgapi.dto.listing.StockForecastResponse;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.service.listing.StockForecastService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@SecurityRequirement(name = "BearerAuth")
@RestController
@RequestMapping(ApiPaths.SELLERS_ME + "/listings")
public class SellerStockForecastController {
    private final StockForecastService forecasts;

    public SellerStockForecastController(StockForecastService forecasts) {
        this.forecasts = forecasts;
    }

    @GetMapping("/stock-forecast")
    public ApiResult<List<StockForecastResponse>> list(AuthPrincipal principal) {
        return ApiResult.success(forecasts.list(principal.userId()));
    }

    @GetMapping("/{listingId}/stock-forecast")
    public ApiResult<StockForecastResponse> detail(@PathVariable long listingId, AuthPrincipal principal) {
        return ApiResult.success(forecasts.detail(principal.userId(), listingId));
    }
}
