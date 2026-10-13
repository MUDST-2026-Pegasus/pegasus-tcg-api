package com.pegasus.pegasustcgapi.controller;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.ApiResult;
import com.pegasus.pegasustcgapi.dto.AdminFinanceViews.AdminPayout;
import com.pegasus.pegasustcgapi.dto.AdminFinanceViews.PayoutSummary;
import com.pegasus.pegasustcgapi.dto.FinanceViews.Payout;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.service.AdminFinanceQueryService;
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
    private final AdminFinanceQueryService finance;

    public AdminPayoutController(PayoutService payouts, AdminFinanceQueryService finance) {
        this.payouts = payouts;
        this.finance = finance;
    }

    @Operation(summary = "List payout requests",
            description = "Every status unless one is given, oldest request first. Open payouts carry "
                    + "the completed sub-orders paying would link; paid ones carry the linked ones.")
    @GetMapping
    public ApiResult<List<AdminPayout>> queue(@RequestParam(required = false) String status) {
        return ApiResult.success(finance.payouts(status));
    }

    @Operation(summary = "Counts for the payout stat strip; this month is cut in Bangkok time")
    @GetMapping("/summary")
    public ApiResult<PayoutSummary> summary() {
        return ApiResult.success(finance.payoutSummary());
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
