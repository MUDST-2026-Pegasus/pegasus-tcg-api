package com.pegasus.pegasustcgapi.controller;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.ApiResult;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.service.PaymentService;
import com.pegasus.pegasustcgapi.service.PaymentService.View;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Payments")
@SecurityRequirement(name = "BearerAuth")
@RestController
@RequestMapping(ApiPaths.PAYMENTS)
public class PaymentController {

    private final PaymentService payments;

    public PaymentController(PaymentService payments) {
        this.payments = payments;
    }

    @Operation(summary = "Start a bank transfer payment")
    @PostMapping
    public ApiResult<View> create(@Valid @RequestBody CreateRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, AuthPrincipal principal) {
        return ApiResult.success(payments.create(principal, request.salesOrderId(), request.method(), key));
    }

    @Operation(summary = "Poll my payment status")
    @GetMapping("/{id}")
    public ApiResult<View> get(@PathVariable long id, AuthPrincipal principal) {
        return ApiResult.success(payments.get(principal, id));
    }

    @Operation(summary = "Submit an uploaded bank transfer slip for admin review")
    @PostMapping("/{id}/slip")
    public ApiResult<View> slip(@PathVariable long id, @Valid @RequestBody SlipRequest request,
            AuthPrincipal principal) {
        return ApiResult.success(payments.attachSlip(principal, id,
                request.slipImageKey(), request.referenceCode()));
    }

    public record CreateRequest(@NotNull Long salesOrderId, @NotBlank String method) {
    }

    public record SlipRequest(@NotBlank String slipImageKey, String referenceCode) {
    }
}
