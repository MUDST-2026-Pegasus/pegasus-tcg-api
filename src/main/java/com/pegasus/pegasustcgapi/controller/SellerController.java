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
@RestController
@RequestMapping(ApiPaths.SELLERS_ME)
public class SellerController {

    private final SellerOnboardingService onboarding;
    private final ShippingOptionService shipping;
    private final PayoutAccountService payoutAccounts;

    public SellerController(SellerOnboardingService onboarding, ShippingOptionService shipping,
            PayoutAccountService payoutAccounts) {
        this.onboarding = onboarding;
        this.shipping = shipping;
        this.payoutAccounts = payoutAccounts;
    }

    // ---------- profile ----------

    @GetMapping
    public ApiResult<SellerProfileResponse> me(AuthPrincipal principal) {
        return ApiResult.success(
                SellerProfileResponse.from(onboarding.requireProfile(principal.userId())));
    }

    /** Creates the profile in NOT_APPLIED. Calling it again returns the same one. */
    @PostMapping("/apply")
    public ApiResult<SellerProfileResponse> apply(AuthPrincipal principal) {
        return ApiResult.success("Seller application started",
                SellerProfileResponse.from(onboarding.startApplication(principal.userId())));
    }

    @PutMapping("/settings")
    public ApiResult<SellerProfileResponse> updateSettings(
            @Valid @RequestBody SellerSettingsRequest request, AuthPrincipal principal) {

        return ApiResult.success("Settings saved",
                SellerProfileResponse.from(onboarding.updateSettings(principal.userId(),
                        request.handlingDays(), request.onVacation(), request.acceptsOrdersAutomatically())));
    }

    // ---------- identity documents ----------

    @GetMapping("/verifications")
    public ApiResult<List<VerificationResponse>> myVerifications(AuthPrincipal principal) {
        return ApiResult.success(onboarding.myVerifications(principal.userId()).stream()
                .map(VerificationResponse::from)
                .toList());
    }

    @PostMapping("/verifications")
    public ResponseEntity<ApiResult<VerificationResponse>> submitVerification(
            @Valid @RequestBody VerificationRequest request, AuthPrincipal principal) {

        VerificationResponse created = VerificationResponse.from(onboarding.submitVerification(
                principal.userId(), request.legalFirstName(), request.legalLastName(),
                request.bankCode(), request.bankName(), request.normalisedAccountNumber()));

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.success("Submitted for review", created));
    }

    // ---------- shipping ----------

    @GetMapping("/shipping-options")
    public ApiResult<List<ShippingOption>> shippingOptions(AuthPrincipal principal) {
        return ApiResult.success(shipping.listMine(principal.userId()));
    }

    @PostMapping("/shipping-options")
    public ResponseEntity<ApiResult<ShippingOption>> createShippingOption(
            @Valid @RequestBody ShippingOptionRequest request, AuthPrincipal principal) {

        ShippingOption created = shipping.create(principal.userId(), request.toFields());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.success("Shipping option added", created));
    }

    @PutMapping("/shipping-options/{optionId}")
    public ApiResult<ShippingOption> updateShippingOption(
            @PathVariable long optionId,
            @Valid @RequestBody ShippingOptionRequest request,
            AuthPrincipal principal) {

        return ApiResult.success("Shipping option updated",
                shipping.update(principal.userId(), optionId, request.toFields()));
    }

    /** Deactivated, not deleted: orders already shipped under it still point here. */
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

    @GetMapping("/payout-account")
    public ApiResult<PayoutAccount> payoutAccount(AuthPrincipal principal) {
        return ApiResult.success(payoutAccounts.mine(principal.userId()));
    }
}
