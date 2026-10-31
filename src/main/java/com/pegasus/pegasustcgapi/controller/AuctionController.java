package com.pegasus.pegasustcgapi.controller;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.ApiResult;
import com.pegasus.pegasustcgapi.dto.AuctionRequests.AuctionCheckout;
import com.pegasus.pegasustcgapi.dto.AuctionRequests.PlaceBid;
import com.pegasus.pegasustcgapi.dto.AuctionViews.AuctionDetail;
import com.pegasus.pegasustcgapi.dto.AuctionViews.AuctionPage;
import com.pegasus.pegasustcgapi.dto.AuctionViews.AuctionWin;
import com.pegasus.pegasustcgapi.dto.AuctionViews.BidHistory;
import com.pegasus.pegasustcgapi.dto.AuctionViews.BidPlaced;
import com.pegasus.pegasustcgapi.dto.AuctionViews.Filter;
import com.pegasus.pegasustcgapi.dto.AuctionViews.MyAuctionPage;
import com.pegasus.pegasustcgapi.dto.AuctionViews.MyTab;
import com.pegasus.pegasustcgapi.dto.AuctionViews.Sort;
import com.pegasus.pegasustcgapi.dto.CheckoutResponse;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Auctions for buyers [TCG-613]. Browsing is open to guests; bidding, buy-now, checkout
 * and the runner-up offer need sign-in.
 *
 * <p>Contract only: every endpoint returns {@link AuctionSamples} until BE-3 to BE-10 add the logic.
 *
 * <p>Who may bid (else 403/400 with the code): a verified email (EMAIL_NOT_VERIFIED), a shipping
 * address (NO_SHIPPING_ADDRESS), no bidding ban (BIDDING_BANNED, details.bannedUntil) and not the
 * seller (OWN_AUCTION).
 */
@Tag(name = "Auctions", description = "Browse auctions, bid, buy now and pay for a win")
@RestController
public class AuctionController {

    private final Clock clock;

    public AuctionController(Clock clock) {
        this.clock = clock;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    @Operation(summary = "List live auctions",
            description = "One filter chip at a time; filterCounts gives every chip's count. q matches the card name "
                    + "or the auction number. Default sort is ENDING_SOON.")
    @GetMapping(ApiPaths.AUCTIONS)
    public ApiResult<AuctionPage> list(
            @RequestParam(required = false) Filter filter,
            @RequestParam(required = false) Short gameId,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "ENDING_SOON") Sort sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResult.success(AuctionSamples.auctionPage(now(), page, size));
    }

    @Operation(summary = "Get one auction",
            description = "Any status. viewer is null for a guest. Poll this every few seconds while the page is open; "
                    + "count down from endsAt corrected by serverTime.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The auction"),
            @ApiResponse(responseCode = "404", description = "AUCTION_NOT_FOUND")
    })
    @GetMapping(ApiPaths.AUCTIONS + "/{id}")
    public ApiResult<AuctionDetail> get(@PathVariable long id, Optional<AuthPrincipal> principal) {
        return ApiResult.success(AuctionSamples.auctionDetail(id, now(), principal.isPresent()));
    }

    @Operation(summary = "Every bid on an auction", description = "Newest first; bidders are masked.")
    @GetMapping(ApiPaths.AUCTIONS + "/{id}/bids")
    public ApiResult<BidHistory> bids(@PathVariable long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Optional<AuthPrincipal> principal) {
        return ApiResult.success(AuctionSamples.bidHistory(now(), page, size));
    }

    @Operation(summary = "Place a bid",
            description = "Final: a bid cannot be withdrawn. A bid in the last 2 minutes resets the clock to 2 minutes "
                    + "(extended=true). The first bid removes buy-now.")
    @SecurityRequirement(name = "BearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Bid accepted"),
            @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED or NO_SHIPPING_ADDRESS"),
            @ApiResponse(responseCode = "403", description = "EMAIL_NOT_VERIFIED, BIDDING_BANNED (details.bannedUntil) "
                    + "or OWN_AUCTION"),
            @ApiResponse(responseCode = "404", description = "AUCTION_NOT_FOUND"),
            @ApiResponse(responseCode = "409", description = "AUCTION_CLOSED, or BID_TOO_LOW with details.minNextBid "
                    + "and details.currentPrice (also when someone bid first)")
    })
    @PostMapping(ApiPaths.AUCTIONS + "/{id}/bids")
    public ResponseEntity<ApiResult<BidPlaced>> placeBid(@PathVariable long id,
            @Valid @RequestBody PlaceBid request, AuthPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.success(AuctionSamples.bidPlaced(id, request.amount(), now())));
    }

    @Operation(summary = "Buy now",
            description = "Ends the auction at the buy-now price. Only while LIVE with no bids. The buyer then pays "
                    + "within 24 hours through POST /auctions/{id}/checkout.")
    @SecurityRequirement(name = "BearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The buyer won the card"),
            @ApiResponse(responseCode = "400", description = "NO_SHIPPING_ADDRESS"),
            @ApiResponse(responseCode = "403", description = "EMAIL_NOT_VERIFIED, BIDDING_BANNED or OWN_AUCTION"),
            @ApiResponse(responseCode = "409", description = "AUCTION_CLOSED or BUY_NOW_UNAVAILABLE")
    })
    @PostMapping(ApiPaths.AUCTIONS + "/{id}/buy-now")
    public ApiResult<AuctionWin> buyNow(@PathVariable long id, AuthPrincipal principal) {
        return ApiResult.success(AuctionSamples.win(id, BigDecimal.valueOf(3500), now()));
    }

    @Operation(summary = "Pay for a won auction",
            description = "Places the order at the locked price and hands over to the ordinary payment flow "
                    + "(transfer + slip / PromptPay). Only the current winner while the payment window is open. "
                    + "Discount codes do not apply.")
    @SecurityRequirement(name = "BearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Order already placed with this idempotency key"),
            @ApiResponse(responseCode = "201", description = "Order placed"),
            @ApiResponse(responseCode = "400", description = "IDEMPOTENCY_KEY_REQUIRED, SHIPPING_OPTION_INVALID"),
            @ApiResponse(responseCode = "403", description = "ACCESS_DENIED: the caller is not the winner"),
            @ApiResponse(responseCode = "409", description = "AUCTION_CLOSED (payment window passed), "
                    + "CART_PRICE_CHANGED or IDEMPOTENCY_KEY_CONFLICT")
    })
    @PostMapping(ApiPaths.AUCTIONS + "/{id}/checkout")
    public ResponseEntity<ApiResult<CheckoutResponse>> checkout(@PathVariable long id,
            @Parameter(description = "Required unique key for this checkout (1–64 characters)", required = true)
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody AuctionCheckout request, AuthPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.success("Order placed successfully", AuctionSamples.checkout(now())));
    }

    @Operation(summary = "Accept the seller's runner-up offer",
            description = "Buy at the caller's own bid; then pay within 24 hours through the auction checkout. "
                    + "Not paying counts as an unpaid win.")
    @SecurityRequirement(name = "BearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The caller now has to pay"),
            @ApiResponse(responseCode = "404", description = "AUCTION_NOT_FOUND, or no offer to the caller"),
            @ApiResponse(responseCode = "409", description = "OFFER_EXPIRED")
    })
    @PostMapping(ApiPaths.AUCTIONS + "/{id}/second-chance/accept")
    public ApiResult<AuctionWin> acceptOffer(@PathVariable long id, AuthPrincipal principal) {
        return ApiResult.success(AuctionSamples.win(id, BigDecimal.valueOf(8300), now()));
    }

    @Operation(summary = "Decline the seller's runner-up offer",
            description = "No penalty. The card goes back to the seller's stock.")
    @SecurityRequirement(name = "BearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Declined"),
            @ApiResponse(responseCode = "409", description = "OFFER_EXPIRED")
    })
    @PostMapping(ApiPaths.AUCTIONS + "/{id}/second-chance/decline")
    public ApiResult<Void> declineOffer(@PathVariable long id, AuthPrincipal principal) {
        return ApiResult.success("Offer declined", null);
    }

    @Operation(summary = "The caller's auctions",
            description = "Every auction the caller has bid on or been offered, newest activity first, with tab counts "
                    + "and the caller's unpaid-win count.")
    @SecurityRequirement(name = "BearerAuth")
    @GetMapping(ApiPaths.ME_AUCTIONS)
    public ApiResult<MyAuctionPage> mine(
            @RequestParam(defaultValue = "ALL") MyTab tab,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            AuthPrincipal principal) {
        return ApiResult.success(AuctionSamples.myAuctions(now(), page, size));
    }
}
