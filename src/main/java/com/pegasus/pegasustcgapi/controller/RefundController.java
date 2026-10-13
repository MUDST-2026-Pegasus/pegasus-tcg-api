package com.pegasus.pegasustcgapi.controller;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.ApiResult;
import com.pegasus.pegasustcgapi.dto.FinanceViews.Refund;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.service.RefundService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Refunds")
@SecurityRequirement(name = "BearerAuth")
@RestController
@RequestMapping(ApiPaths.API_V1 + "/refunds")
public class RefundController {

    private final RefundService refunds;

    public RefundController(RefundService refunds) {
        this.refunds = refunds;
    }

    @GetMapping
    public ApiResult<List<Refund>> mine(AuthPrincipal principal) {
        return ApiResult.success(refunds.mine(principal.userId()).stream().map(Refund::from).toList());
    }

    @GetMapping("/{id}")
    public ApiResult<Refund> get(@PathVariable long id, AuthPrincipal principal) {
        return ApiResult.success(Refund.from(refunds.mine(id, principal.userId())));
    }

    @Operation(summary = "Set the buyer's bank account for a pending refund")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Refund destination saved"),
            @ApiResponse(responseCode = "400", description = "Invalid bank details"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "404", description = "Refund not found or does not belong to the buyer"),
            @ApiResponse(responseCode = "409", description = "Refund is not an approved bank transfer")
    })
    @PutMapping("/{id}/destination")
    public ApiResult<Refund> destination(@PathVariable long id, @Valid @RequestBody Destination body,
            AuthPrincipal principal) {
        return ApiResult.success(Refund.from(refunds.setDestination(id, principal.userId(),
                body.bankName(), body.accountName(), body.accountNumber())));
    }

    public record Destination(@NotBlank String bankName, @NotBlank String accountName,
            @NotBlank String accountNumber) {}
}
