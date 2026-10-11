package com.pegasus.pegasustcgapi.controller;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.ApiResult;
import com.pegasus.pegasustcgapi.dto.FinanceViews.Refund;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.service.RefundService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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

    public AdminRefundController(RefundService refunds) {
        this.refunds = refunds;
    }

    @Operation(summary = "List manual refunds awaiting transfer")
    @GetMapping
    public ApiResult<List<Refund>> queue(@RequestParam(required = false) String status) {
        return ApiResult.success(refunds.queue(status).stream().map(Refund::from).toList());
    }

    @Operation(summary = "Confirm the money was sent to the buyer outside the system")
    @PostMapping("/{id}/processed")
    public ApiResult<Refund> processed(@PathVariable long id, AuthPrincipal principal) {
        return ApiResult.success(Refund.from(refunds.processed(id, principal.userId())));
    }
}
