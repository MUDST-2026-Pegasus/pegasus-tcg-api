package com.pegasus.pegasustcgapi.controller;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.ApiResult;
import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.dto.RejectVerificationRequest;
import com.pegasus.pegasustcgapi.dto.VerificationResponse;
import com.pegasus.pegasustcgapi.model.VerificationStatus;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.service.SellerOnboardingService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The KYC review queue [RQ-1].
 *
 * <p>Approving is the one action that grants the SELLER role, so this is the
 * narrow gate everything on the selling side sits behind.
 */
@RestController
@RequestMapping(ApiPaths.ADMIN + "/verifications")
@PreAuthorize("hasRole('ADMIN')")
public class AdminVerificationController {

    private final SellerOnboardingService onboarding;

    public AdminVerificationController(SellerOnboardingService onboarding) {
        this.onboarding = onboarding;
    }

    /** Oldest first, so nobody waits behind a later submission. */
    @GetMapping
    public ApiResult<PageResponse<VerificationResponse>> queue(
            @RequestParam(defaultValue = "SUBMITTED") VerificationStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        List<VerificationResponse> items = onboarding.queue(status, page, size).stream()
                .map(VerificationResponse::from)
                .toList();

        return ApiResult.success(
                PageResponse.of(items, page, size, onboarding.queueSize(status)));
    }

    /** Claims the request, so a second admin opening the queue sees it is taken. */
    @PostMapping("/{verificationId}/start-review")
    public ApiResult<VerificationResponse> startReview(
            @PathVariable long verificationId, AuthPrincipal principal) {

        return ApiResult.success("Review started", VerificationResponse.from(
                onboarding.startReview(verificationId, principal.userId())));
    }

    /** Grants the SELLER role and marks the profile VERIFIED, in one transaction. */
    @PostMapping("/{verificationId}/approve")
    public ApiResult<VerificationResponse> approve(
            @PathVariable long verificationId, AuthPrincipal principal) {

        return ApiResult.success("Seller verified", VerificationResponse.from(
                onboarding.approve(verificationId, principal.userId())));
    }

    /** The seller can submit a new document afterwards; the reason tells them what to fix. */
    @PostMapping("/{verificationId}/reject")
    public ApiResult<VerificationResponse> reject(
            @PathVariable long verificationId,
            @Valid @RequestBody RejectVerificationRequest request,
            AuthPrincipal principal) {

        return ApiResult.success("Verification rejected", VerificationResponse.from(
                onboarding.reject(verificationId, principal.userId(), request.reason())));
    }
}
