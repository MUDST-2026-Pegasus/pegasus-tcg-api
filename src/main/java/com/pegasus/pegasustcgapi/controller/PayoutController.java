package com.pegasus.pegasustcgapi.controller;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.ApiResult;
import com.pegasus.pegasustcgapi.dto.FinanceViews.Payout;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.service.PayoutService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Seller Payouts")
@SecurityRequirement(name = "BearerAuth")
@RestController
@RequestMapping(ApiPaths.SELLERS_ME + "/payouts")
public class PayoutController {

    private final PayoutService payouts;

    public PayoutController(PayoutService payouts) {
        this.payouts = payouts;
    }

    @Operation(summary = "My payout requests")
    @GetMapping
    public ApiResult<List<Payout>> mine(AuthPrincipal principal) {
        return ApiResult.success(payouts.mine(principal.userId()).stream().map(Payout::from).toList());
    }

    @Operation(summary = "Request a manual transfer of withdrawable earnings")
    @PostMapping
    public ApiResult<Payout> request(@Valid @RequestBody Request body, AuthPrincipal principal) {
        return ApiResult.success(Payout.from(payouts.request(principal.userId(), body.amount())));
    }

    public record Request(@NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2)
            BigDecimal amount) {
    }
}
