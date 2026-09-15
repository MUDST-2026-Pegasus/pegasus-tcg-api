package com.pegasus.pegasustcgapi.controller;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.dto.RejectVerificationRequest;
import com.pegasus.pegasustcgapi.dto.VerificationResponse;
import com.pegasus.pegasustcgapi.model.VerificationStatus;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.service.SellerOnboardingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Seller Verification Queue (Admin)", description = "KYC review queue, approval and rejection actions (Admin only)")
@RestController
@RequestMapping(ApiPaths.ADMIN + "/verifications")
@PreAuthorize("hasRole('ADMIN')")
public class AdminVerificationController {

    private final SellerOnboardingService onboarding;

    public AdminVerificationController(SellerOnboardingService onboarding) {
        this.onboarding = onboarding;
    }

    /** Oldest first, so nobody waits behind a later submission. */
    @Operation(summary = "List verification review queue", description = "Retrieves a paginated list of KYC verification submissions filtered by status.")
    @ApiResponse(responseCode = "200", description = "Queue page retrieved")
    @GetMapping
    public com.pegasus.pegasustcgapi.common.ApiResponse<PageResponse<VerificationResponse>> queue(
            @Parameter(description = "Verification review status") @RequestParam(defaultValue = "SUBMITTED") VerificationStatus status,
            @Parameter(description = "Zero-indexed page number", example = "0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size", example = "20") @RequestParam(defaultValue = "20") int size) {

        List<VerificationResponse> items = onboarding.queue(status, page, size).stream()
                .map(VerificationResponse::from)
                .toList();

        return com.pegasus.pegasustcgapi.common.ApiResponse.success(
                PageResponse.of(items, page, size, onboarding.queueSize(status)));
    }

    /** Claims the request, so a second admin opening the queue sees it is taken. */
    @Operation(summary = "Start reviewing verification", description = "Claims a verification submission and marks it as IN_REVIEW.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Review started"),
            @ApiResponse(responseCode = "400", description = "Submission not in SUBMITTED state"),
            @ApiResponse(responseCode = "404", description = "Verification submission not found")
    })
    @PostMapping("/{verificationId}/start-review")
    public com.pegasus.pegasustcgapi.common.ApiResponse<VerificationResponse> startReview(
            @Parameter(description = "Verification ID", example = "1") @PathVariable long verificationId,
            AuthPrincipal principal) {

        return com.pegasus.pegasustcgapi.common.ApiResponse.success("Review started", VerificationResponse.from(
                onboarding.startReview(verificationId, principal.userId())));
    }

    /** Grants the SELLER role and marks the profile VERIFIED, in one transaction. */
    @Operation(summary = "Approve verification", description = "Approves a seller KYC submission and automatically grants the SELLER role.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Seller verified"),
            @ApiResponse(responseCode = "400", description = "Submission not in valid state for approval"),
            @ApiResponse(responseCode = "404", description = "Verification submission not found")
    })
    @PostMapping("/{verificationId}/approve")
    public com.pegasus.pegasustcgapi.common.ApiResponse<VerificationResponse> approve(
            @Parameter(description = "Verification ID", example = "1") @PathVariable long verificationId,
            AuthPrincipal principal) {

        return com.pegasus.pegasustcgapi.common.ApiResponse.success("Seller verified", VerificationResponse.from(
                onboarding.approve(verificationId, principal.userId())));
    }

    /** The seller can submit a new document afterwards; the reason tells them what to fix. */
    @Operation(summary = "Reject verification", description = "Rejects a seller KYC submission with a human-readable reason.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Verification rejected"),
            @ApiResponse(responseCode = "400", description = "Validation failed or submission not in review"),
            @ApiResponse(responseCode = "404", description = "Verification submission not found")
    })
    @PostMapping("/{verificationId}/reject")
    public com.pegasus.pegasustcgapi.common.ApiResponse<VerificationResponse> reject(
            @Parameter(description = "Verification ID", example = "1") @PathVariable long verificationId,
            @Valid @RequestBody RejectVerificationRequest request,
            AuthPrincipal principal) {

        return com.pegasus.pegasustcgapi.common.ApiResponse.success("Verification rejected", VerificationResponse.from(
                onboarding.reject(verificationId, principal.userId(), request.reason())));
    }
}
