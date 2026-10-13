package com.pegasus.pegasustcgapi.controller;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.ApiResult;
import com.pegasus.pegasustcgapi.dto.AdminFinanceViews.AdminRefund;
import com.pegasus.pegasustcgapi.dto.AdminFinanceViews.RefundSummary;
import com.pegasus.pegasustcgapi.dto.FinanceViews.Refund;
import com.pegasus.pegasustcgapi.dto.FinanceViews.RefundTransferAttempt;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.service.AdminFinanceQueryService;
import com.pegasus.pegasustcgapi.service.RefundService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
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

@Tag(name = "Refunds (Admin)")
@SecurityRequirement(name = "BearerAuth")
@RestController
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping(ApiPaths.ADMIN + "/refunds")
public class AdminRefundController {

    private final RefundService refunds;
    private final AdminFinanceQueryService finance;

    public AdminRefundController(RefundService refunds, AdminFinanceQueryService finance) {
        this.refunds = refunds;
        this.finance = finance;
    }

    @Operation(summary = "List manual refunds awaiting transfer",
            description = "Defaults to APPROVED, oldest first; status=ALL lists every refund newest first.")
    @GetMapping
    public ApiResult<List<AdminRefund>> queue(@RequestParam(required = false) String status) {
        return ApiResult.success(finance.refunds(status));
    }

    @Operation(summary = "Counts for the refund stat strip; this month is cut in Bangkok time")
    @GetMapping("/summary")
    public ApiResult<RefundSummary> summary() {
        return ApiResult.success(finance.refundSummary());
    }

    @GetMapping("/{id}/transfers")
    @Operation(summary = "List transfer attempts for a refund")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Transfer history retrieved"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "Admin role required"),
            @ApiResponse(responseCode = "404", description = "Refund not found")
    })
    public ApiResult<List<RefundTransferAttempt>> transfers(@PathVariable long id) {
        return ApiResult.success(refunds.transferHistory(id).stream()
                .map(RefundTransferAttempt::from).toList());
    }

    @Operation(summary = "Lock the buyer's bank destination before making the external transfer")
    @PostMapping("/{id}/processing")
    public ApiResult<Refund> processing(@PathVariable long id, @Valid @RequestBody Processing body,
            AuthPrincipal principal) {
        return ApiResult.success(Refund.from(refunds.startTransfer(
                id, principal.userId(), body.accountNumber())));
    }

    @Operation(summary = "Confirm the money was sent to the buyer outside the system")
    @PostMapping("/{id}/processed")
    public ApiResult<Refund> processed(@PathVariable long id, @Valid @RequestBody Processed body,
            AuthPrincipal principal) {
        return ApiResult.success(Refund.from(refunds.processed(id, principal.userId(), body.transferReference())));
    }

    @Operation(summary = "Record a failed transfer attempt and let the buyer correct their bank details")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Failed transfer recorded"),
            @ApiResponse(responseCode = "400", description = "Reason is blank or exceeds 255 characters"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "Admin role required"),
            @ApiResponse(responseCode = "404", description = "Refund not found"),
            @ApiResponse(responseCode = "409", description = "Refund is not processing a transfer")
    })
    @PostMapping("/{id}/failed")
    public ApiResult<Refund> failed(@PathVariable long id, @Valid @RequestBody Failed body,
            AuthPrincipal principal) {
        return ApiResult.success(Refund.from(refunds.transferFailed(id, principal.userId(), body.reason())));
    }

    public record Processed(@NotBlank String transferReference) {}
    public record Processing(@NotBlank String accountNumber) {}
    public record Failed(@NotBlank String reason) {}
}
