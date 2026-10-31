package com.pegasus.pegasustcgapi.controller;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.ApiResult;
import com.pegasus.pegasustcgapi.dto.AuctionRequests.CreateAuction;
import com.pegasus.pegasustcgapi.dto.AuctionRequests.DecisionChoice;
import com.pegasus.pegasustcgapi.dto.AuctionRequests.SellerDecision;
import com.pegasus.pegasustcgapi.dto.AuctionRequests.UpdateAuction;
import com.pegasus.pegasustcgapi.dto.SellerAuctionViews.EligibleUnitPage;
import com.pegasus.pegasustcgapi.dto.SellerAuctionViews.SellerAuctionDetail;
import com.pegasus.pegasustcgapi.dto.SellerAuctionViews.SellerAuctionPage;
import com.pegasus.pegasustcgapi.model.AuctionStatus;
import com.pegasus.pegasustcgapi.model.CardCondition;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in shop's own auctions [TCG-613]. Another shop's auction is 404.
 *
 * <p>Contract only: every endpoint returns {@link AuctionSamples} until BE-2 and BE-9 add the logic.
 */
@Tag(name = "Seller Auctions", description = "Open, manage and follow up a shop's own auctions")
@SecurityRequirement(name = "BearerAuth")
@RestController
@RequestMapping(ApiPaths.SELLERS_ME_AUCTIONS)
public class SellerAuctionController {

    private final Clock clock;

    public SellerAuctionController(Clock clock) {
        this.clock = clock;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    @Operation(summary = "List the shop's auctions",
            description = "Newest first. statusCounts covers every status, for the tabs; kpis fills the row above.")
    @GetMapping
    public ApiResult<SellerAuctionPage> list(
            @RequestParam(required = false) AuctionStatus status,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            AuthPrincipal principal) {
        return ApiResult.success(AuctionSamples.sellerAuctions(now(), page, size));
    }

    @Operation(summary = "Stock cards for the new-auction form",
            description = "Every card in hand, selectable ones first. LISTED cards are selectable and come off their "
                    + "listing when the auction opens; IN_AUCTION and RESERVED ones are shown with heldBy.")
    @GetMapping("/eligible-units")
    public ApiResult<EligibleUnitPage> eligibleUnits(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Short gameId,
            @RequestParam(required = false) CardCondition condition,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            AuthPrincipal principal) {
        return ApiResult.success(AuctionSamples.eligibleUnits(page, size));
    }

    @Operation(summary = "Open an auction",
            description = "Opens at once and locks the card. The shop must be VERIFIED.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Auction opened"),
            @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED, AUCTION_END_TOO_SOON, "
                    + "AUCTION_END_TOO_FAR or BUY_NOW_BELOW_START"),
            @ApiResponse(responseCode = "403", description = "SELLER_NOT_VERIFIED"),
            @ApiResponse(responseCode = "404", description = "LISTING_UNIT_NOT_FOUND"),
            @ApiResponse(responseCode = "409", description = "UNIT_NOT_AVAILABLE")
    })
    @PostMapping
    public ResponseEntity<ApiResult<SellerAuctionDetail>> create(@Valid @RequestBody CreateAuction request,
            AuthPrincipal principal) {
        SellerAuctionDetail created = AuctionSamples.created(160, now(), request.startingPrice(),
                request.bidIncrement(), request.endsAt(), request.buyNowPrice(), request.sellerNote());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResult.success(created));
    }

    @Operation(summary = "Get one of the shop's auctions",
            description = "Any status. Sample data: id 119 shows a pending decision after the winner did not pay.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The auction"),
            @ApiResponse(responseCode = "404", description = "AUCTION_NOT_FOUND")
    })
    @GetMapping("/{id}")
    public ApiResult<SellerAuctionDetail> get(@PathVariable long id, AuthPrincipal principal) {
        return ApiResult.success(AuctionSamples.sellerDetail(id, now()));
    }

    @Operation(summary = "Change buy-now or the note",
            description = "Send both fields. Only while LIVE with no bids.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Saved"),
            @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED or BUY_NOW_BELOW_START"),
            @ApiResponse(responseCode = "409", description = "AUCTION_HAS_BIDS or AUCTION_CLOSED")
    })
    @PatchMapping("/{id}")
    public ApiResult<SellerAuctionDetail> update(@PathVariable long id, @Valid @RequestBody UpdateAuction request,
            AuthPrincipal principal) {
        SellerAuctionDetail d = AuctionSamples.created(id, now(), BigDecimal.valueOf(2400),
                BigDecimal.valueOf(100), now().plusDays(3), request.buyNowPrice(), request.sellerNote());
        return ApiResult.success(d);
    }

    @Operation(summary = "Cancel an auction",
            description = "Only while LIVE with no bids; the card goes back to stock. After the first bid only an "
                    + "admin can stop it.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Cancelled"),
            @ApiResponse(responseCode = "409", description = "AUCTION_HAS_BIDS or AUCTION_CLOSED")
    })
    @PostMapping("/{id}/cancel")
    public ApiResult<SellerAuctionDetail> cancel(@PathVariable long id, AuthPrincipal principal) {
        SellerAuctionDetail d = AuctionSamples.sellerDetail(150, now());
        return ApiResult.success(AuctionSamples.withStatus(d, AuctionStatus.CANCELLED, null, now()));
    }

    @Operation(summary = "Choose what happens after the winner did not pay",
            description = "Only while AWAITING_SELLER_DECISION. OFFER_TO_SECOND sends the runner-up an offer at their "
                    + "bid (24 hours to answer); RELIST puts the card back in stock so the app can open the "
                    + "new-auction form with the old settings.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Choice recorded"),
            @ApiResponse(responseCode = "409", description = "NO_SECOND_BIDDER, or AUCTION_CLOSED when the time "
                    + "to choose has passed")
    })
    @PostMapping("/{id}/decision")
    public ApiResult<SellerAuctionDetail> decide(@PathVariable long id, @Valid @RequestBody SellerDecision request,
            AuthPrincipal principal) {
        SellerAuctionDetail d = AuctionSamples.sellerDetail(119, now());
        SellerAuctionDetail result = request.choice() == DecisionChoice.OFFER_TO_SECOND
                ? AuctionSamples.withStatus(d, AuctionStatus.OFFERED_TO_SECOND, AuctionSamples.sentOffer(now()), now())
                : AuctionSamples.withStatus(d, AuctionStatus.UNSOLD, null, now());
        return ApiResult.success(result);
    }
}
