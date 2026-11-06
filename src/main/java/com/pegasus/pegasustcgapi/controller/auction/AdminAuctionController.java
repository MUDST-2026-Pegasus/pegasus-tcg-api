package com.pegasus.pegasustcgapi.controller.auction;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.ApiResult;
import com.pegasus.pegasustcgapi.dto.auction.AdminAuctionViews.AdminAction;
import com.pegasus.pegasustcgapi.dto.auction.AdminAuctionViews.AdminAuctionDetail;
import com.pegasus.pegasustcgapi.dto.auction.AdminAuctionViews.AdminAuctionPage;
import com.pegasus.pegasustcgapi.dto.auction.AdminAuctionViews.BanTab;
import com.pegasus.pegasustcgapi.dto.auction.AdminAuctionViews.BannedBidder;
import com.pegasus.pegasustcgapi.dto.auction.AdminAuctionViews.BannedBidderPage;
import com.pegasus.pegasustcgapi.dto.auction.AdminAuctionViews.Tab;
import com.pegasus.pegasustcgapi.dto.auction.AuctionRequests.LiftBiddingBan;
import com.pegasus.pegasustcgapi.dto.auction.AuctionRequests.SuspendAuction;
import com.pegasus.pegasustcgapi.model.auction.AuctionStatus;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.service.auction.AdminAuctionService;
import com.pegasus.pegasustcgapi.service.auction.AdminAuctionReadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.OffsetDateTime;
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
 * Every auction on the platform, and the users barred from bidding [TCG-613].
 *
 * Reads auction administration data and performs moderation actions.
 */
@Tag(name = "Auctions (Admin)", description = "Watch auctions, suspend rule-breakers and manage bidding bans")
@SecurityRequirement(name = "BearerAuth")
@RestController
@RequestMapping(ApiPaths.ADMIN_AUCTIONS)
@PreAuthorize("hasRole('ADMIN')")
public class AdminAuctionController {

    private final AdminAuctionService actions;
    private final AdminAuctionReadService reads;

    public AdminAuctionController(AdminAuctionService actions, AdminAuctionReadService reads) {
        this.actions = actions;
        this.reads = reads;
    }

    @Operation(summary = "List every auction",
            description = "q matches the card, the shop or the auction number. tabCounts covers every tab.")
    @GetMapping
    public ApiResult<AdminAuctionPage> list(
            @RequestParam(defaultValue = "ALL") Tab tab,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResult.success(reads.list(tab, q, page, size));
    }

    @Operation(summary = "Get one auction with full bidder names and signals")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The auction"),
            @ApiResponse(responseCode = "404", description = "AUCTION_NOT_FOUND")
    })
    @GetMapping("/{id}")
    public ApiResult<AdminAuctionDetail> get(@PathVariable long id) {
        return ApiResult.success(reads.detail(id));
    }

    @Operation(summary = "Suspend an auction",
            description = "Any open status. Cancels every bid, puts the card back in the seller's stock, tells the "
                    + "seller and bidders the reason, and writes the activity log.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Suspended"),
            @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED: reason is required"),
            @ApiResponse(responseCode = "409", description = "AUCTION_CLOSED: already sold, unsold, cancelled "
                    + "or suspended")
    })
    @PostMapping("/{id}/suspend")
    public ApiResult<AdminAuctionDetail> suspend(@PathVariable long id, @Valid @RequestBody SuspendAuction request,
            AuthPrincipal principal) {
        actions.suspend(id, principal.userId(), request.reason());
        return ApiResult.success(reads.detail(id));
    }

    @Operation(summary = "Users with unpaid-win strikes",
            description = "BANNED: barred now. AT_RISK: two strikes. HISTORY: bans lifted or served out.")
    @GetMapping("/banned-bidders")
    public ApiResult<BannedBidderPage> bannedBidders(
            @RequestParam(defaultValue = "BANNED") BanTab tab,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResult.success(reads.bannedBidders(tab, q, page, size));
    }

    @Operation(summary = "Lift a bidding ban",
            description = "For when not paying was not the user's fault, e.g. a payment outage. Clears the strikes "
                    + "and writes the activity log.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Lifted"),
            @ApiResponse(responseCode = "404", description = "USER_NOT_FOUND")
    })
    @PostMapping("/banned-bidders/{userId}/lift")
    public ApiResult<BannedBidder> liftBan(@PathVariable long userId,
            @Valid @RequestBody(required = false) LiftBiddingBan request, AuthPrincipal principal) {
        actions.liftBan(userId, principal.userId(), request == null || request.reason() == null
                || request.reason().isBlank() ? "Admin lifted bidding ban" : request.reason());
        return ApiResult.success(reads.bannedBidder(userId));
    }
}
