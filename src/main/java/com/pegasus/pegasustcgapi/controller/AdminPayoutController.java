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
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Payouts (Admin)")
@SecurityRequirement(name = "BearerAuth")
@RestController
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping(ApiPaths.ADMIN + "/payouts")
public class AdminPayoutController {

    private final PayoutService payouts;

    public AdminPayoutController(PayoutService payouts) {
        this.payouts = payouts;
    }

    @GetMapping
    public ApiResult<List<Payout>> queue(@RequestParam(required = false) String status) {
        return ApiResult.success(payouts.queue(status).stream().map(Payout::from).toList());
    }

    @Operation(summary = "Approve a payout request")
    @PostMapping("/{id}/approve")
    public ApiResult<Payout> approve(@PathVariable long id, AuthPrincipal principal) {
        return ApiResult.success(Payout.from(payouts.approve(id, principal.userId())));
    }

    @PostMapping("/{id}/processing")
    public ApiResult<Payout> processing(@PathVariable long id) {
        return ApiResult.success(Payout.from(payouts.processing(id)));
    }

    @PostMapping("/{id}/paid")
    public ApiResult<Payout> paid(@PathVariable long id, @Valid @RequestBody Paid body) {
        return ApiResult.success(Payout.from(payouts.paid(id, body.transferReference(), body.slipImageKey())));
    }

    @PostMapping("/{id}/reject")
    public ApiResult<Payout> reject(@PathVariable long id, @Valid @RequestBody Reason body) {
        return ApiResult.success(Payout.from(payouts.reject(id, body.reason())));
    }

    @PostMapping("/{id}/fail")
    public ApiResult<Payout> fail(@PathVariable long id, @Valid @RequestBody Reason body) {
        return ApiResult.success(Payout.from(payouts.fail(id, body.reason())));
    }

    public record Paid(@NotBlank String transferReference, @NotBlank String slipImageKey) {
    }

    public record Reason(@NotBlank String reason) {
    }
}
