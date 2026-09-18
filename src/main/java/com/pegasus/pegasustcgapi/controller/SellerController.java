package com.pegasus.pegasustcgapi.controller;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.ApiResult;
import com.pegasus.pegasustcgapi.dto.SellerProfileResponse;
import com.pegasus.pegasustcgapi.dto.SellerSettingsRequest;
import com.pegasus.pegasustcgapi.dto.ShippingOptionRequest;
import com.pegasus.pegasustcgapi.dto.VerificationRequest;
import com.pegasus.pegasustcgapi.dto.VerificationResponse;
import com.pegasus.pegasustcgapi.model.PayoutAccount;
import com.pegasus.pegasustcgapi.model.ShippingOption;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.service.PayoutAccountService;
import com.pegasus.pegasustcgapi.service.SellerOnboardingService;
import com.pegasus.pegasustcgapi.service.ShippingOptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.pegasus.pegasustcgapi.storage.StorageService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Everything the signed-in user manages about their own selling: applying,
 * identity documents, delivery options and where the money goes.
 *
 * <p>No role check on the class. Applying is open to any account — that is the
 * point of one profile being both buyer and seller [RQ-2] — and the endpoints
 * that genuinely need a verified seller say so themselves.
 */
@Tag(name = "Seller Profile & Settings", description = "Seller onboarding, identity verification submissions, shipping options, and payout accounts")
@SecurityRequirement(name = "BearerAuth")
@RestController
@RequestMapping(ApiPaths.SELLERS_ME)
public class SellerController {

    private final SellerOnboardingService onboarding;
    private final ShippingOptionService shipping;
    private final PayoutAccountService payoutAccounts;
    private final StorageService storageService;

    public SellerController(SellerOnboardingService onboarding, ShippingOptionService shipping,
            PayoutAccountService payoutAccounts, StorageService storageService) {
        this.onboarding = onboarding;
        this.shipping = shipping;
        this.payoutAccounts = payoutAccounts;
        this.storageService = storageService;
    }

    private VerificationResponse toResponse(com.pegasus.pegasustcgapi.model.SellerVerification v) {
        String url = storageService.presignDownload(v.bankBookImageKey());
        return VerificationResponse.from(v, url);
    }

    // ---------- profile ----------

    @Operation(summary = "Get current seller profile", description = "Retrieves seller onboarding profile, status, and settings for the authenticated user.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Seller profile retrieved"),
            @ApiResponse(responseCode = "404", description = "Seller profile not yet created")
    })
    @GetMapping
    public ApiResult<SellerProfileResponse> me(AuthPrincipal principal) {
        return ApiResult.success(
                SellerProfileResponse.from(onboarding.requireProfile(principal.userId())));
    }

    /** Creates the profile in NOT_APPLIED. Calling it again returns the same one. */
    @Operation(summary = "Start seller application", description = "Initializes seller onboarding profile in NOT_APPLIED status.")
    @ApiResponse(responseCode = "200", description = "Application started or existing profile returned")
    @PostMapping("/apply")
    public ApiResult<SellerProfileResponse> apply(AuthPrincipal principal) {
        return ApiResult.success("Seller application started",
                SellerProfileResponse.from(onboarding.startApplication(principal.userId())));
    }

    @Operation(summary = "Update seller settings", description = "Updates handling days, vacation mode, and automatic order acceptance.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Settings updated successfully"),
            @ApiResponse(responseCode = "400", description = "Validation failed")
    })
    @PutMapping("/settings")
    public ApiResult<SellerProfileResponse> updateSettings(
            @Valid @RequestBody SellerSettingsRequest request, AuthPrincipal principal) {

        return ApiResult.success("Settings saved",
                SellerProfileResponse.from(onboarding.updateSettings(principal.userId(),
                        request.handlingDays(), request.onVacation(), request.acceptsOrdersAutomatically())));
    }

    // ---------- identity documents ----------

    @Operation(summary = "List seller verification submissions", description = "Retrieves all KYC verification documents submitted by the authenticated seller.")
    @ApiResponse(responseCode = "200", description = "Verifications listed")
    @GetMapping("/verifications")
    public ApiResult<List<VerificationResponse>> myVerifications(AuthPrincipal principal) {
        return ApiResult.success(onboarding.myVerifications(principal.userId()).stream()
                .map(this::toResponse)
                .toList());
    }

    @Operation(summary = "Submit KYC verification", description = "Submits legal identity and bank account details for seller verification review.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Verification submitted for review"),
            @ApiResponse(responseCode = "400", description = "Request payload failed validation"),
            @ApiResponse(responseCode = "403", description = "Suspended seller cannot submit verification"),
            @ApiResponse(responseCode = "409", description = "Verification already pending review or bank account already registered")
    })
    @PostMapping("/verifications")
    public ResponseEntity<ApiResult<VerificationResponse>> submitVerification(
            @Valid @RequestBody VerificationRequest request, AuthPrincipal principal) {

        VerificationResponse created = toResponse(onboarding.submitVerification(
                principal.userId(), request.legalFirstName(), request.legalLastName(),
                request.bankCode(), request.bankName(), request.normalisedAccountNumber(),
                request.bankBookImageKey()));

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.success("Submitted for review", created));
    }

    // ---------- shipping ----------

    @Operation(summary = "List shipping options", description = "Retrieves all shipping and delivery options configured by the authenticated seller.")
    @ApiResponse(responseCode = "200", description = "Shipping options listed")
    @GetMapping("/shipping-options")
    public ApiResult<List<ShippingOption>> shippingOptions(AuthPrincipal principal) {
        return ApiResult.success(shipping.listMine(principal.userId()));
    }

    @Operation(summary = "Create shipping option", description = "Creates a new shipping option with courier name and rates.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Shipping option added"),
            @ApiResponse(responseCode = "400", description = "Validation failed")
    })
    @PostMapping("/shipping-options")
    public ResponseEntity<ApiResult<ShippingOption>> createShippingOption(
            @Valid @RequestBody ShippingOptionRequest request, AuthPrincipal principal) {

        ShippingOption created = shipping.create(principal.userId(), request.toFields());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.success("Shipping option added", created));
    }

    @Operation(summary = "Update shipping option", description = "Updates an existing shipping option's name, cost, or configuration.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Shipping option updated"),
            @ApiResponse(responseCode = "400", description = "Validation failed"),
            @ApiResponse(responseCode = "404", description = "Shipping option not found")
    })
    @PutMapping("/shipping-options/{optionId}")
    public ApiResult<ShippingOption> updateShippingOption(
            @PathVariable long optionId,
            @Valid @RequestBody ShippingOptionRequest request,
            AuthPrincipal principal) {

        return ApiResult.success("Shipping option updated",
                shipping.update(principal.userId(), optionId, request.toFields()));
    }

    /** Deactivated, not deleted: orders already shipped under it still point here. */
    @Operation(summary = "Deactivate shipping option", description = "Deactivates a shipping option so it cannot be selected for new orders.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Shipping option deactivated"),
            @ApiResponse(responseCode = "404", description = "Shipping option not found")
    })
    @DeleteMapping("/shipping-options/{optionId}")
    public ApiResult<Void> deactivateShippingOption(
            @PathVariable long optionId, AuthPrincipal principal) {

        shipping.deactivate(principal.userId(), optionId);
        return ApiResult.success("Shipping option deactivated", null);
    }

    // ---------- payout account ----------
    //
    // Read-only, and singular. A seller has one account, it is the one their
    // verification approved, and changing bank means verifying a new one — so
    // there is nothing to add, choose between, or delete.

    @Operation(summary = "Get payout account", description = "Retrieves the verified seller's active bank payout account.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Payout account retrieved"),
            @ApiResponse(responseCode = "404", description = "Payout account not found")
    })
    @GetMapping("/payout-account")
    public ApiResult<PayoutAccount> payoutAccount(AuthPrincipal principal) {
        return ApiResult.success(payoutAccounts.mine(principal.userId()));
    }
}
