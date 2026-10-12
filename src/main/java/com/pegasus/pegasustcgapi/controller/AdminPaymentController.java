package com.pegasus.pegasustcgapi.controller;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.ApiResult;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.service.PaymentService;
import com.pegasus.pegasustcgapi.service.PaymentService.ReviewView;
import com.pegasus.pegasustcgapi.service.PaymentService.View;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Payments (Admin)")
@SecurityRequirement(name = "BearerAuth")
@RestController
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping(ApiPaths.ADMIN + "/payments")
public class AdminPaymentController {

    private final PaymentService payments;

    public AdminPaymentController(PaymentService payments) {
        this.payments = payments;
    }

    @Operation(summary = "List bank transfer slips awaiting review")
    @GetMapping
    public ApiResult<List<ReviewView>> queue(@RequestParam(required = false) String status) {
        return ApiResult.success(payments.queue(status));
    }

    @Operation(summary = "Approve or reject a bank transfer after checking the bank account")
    @PostMapping("/{id}/verify")
    public ApiResult<View> verify(@PathVariable long id, @Valid @RequestBody VerifyRequest request,
            AuthPrincipal principal) {
        return ApiResult.success(payments.verify(principal.userId(), id,
                request.approved(), request.reason()));
    }

    public record VerifyRequest(@NotNull Boolean approved, String reason) {
    }
}
