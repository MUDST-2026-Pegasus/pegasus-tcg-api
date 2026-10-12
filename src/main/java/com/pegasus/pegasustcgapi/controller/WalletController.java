package com.pegasus.pegasustcgapi.controller;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.ApiResult;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.service.WalletLedgerService;
import com.pegasus.pegasustcgapi.service.WalletLedgerService.WalletView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Wallet", description = "Seller balance and immutable ledger history")
@SecurityRequirement(name = "BearerAuth")
@RestController
@RequestMapping(ApiPaths.API_V1 + "/me/wallet")
public class WalletController {

    private final WalletLedgerService wallet;

    public WalletController(WalletLedgerService wallet) {
        this.wallet = wallet;
    }

    @Operation(summary = "View my wallet")
    @GetMapping
    public ApiResult<WalletView> mine(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, AuthPrincipal principal) {
        return ApiResult.success(wallet.sellerWallet(principal.userId(), page, size));
    }
}
