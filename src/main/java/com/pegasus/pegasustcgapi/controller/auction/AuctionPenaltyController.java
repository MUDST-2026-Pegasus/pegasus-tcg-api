package com.pegasus.pegasustcgapi.controller.auction;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.ApiResult;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.service.auction.BidderStrikeService;
import com.pegasus.pegasustcgapi.service.auction.BidderStrikeService.PenaltyStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuctionPenaltyController {
    private final BidderStrikeService strikes;
    private final Clock clock;

    public AuctionPenaltyController(BidderStrikeService strikes, Clock clock) {
        this.strikes = strikes;
        this.clock = clock;
    }

    @Operation(summary = "The caller's unpaid auction wins and bidding ban")
    @SecurityRequirement(name = "BearerAuth")
    @GetMapping(ApiPaths.ME_AUCTIONS + "/penalty")
    public ApiResult<PenaltyStatus> penalty(AuthPrincipal principal) {
        return ApiResult.success(strikes.status(principal.userId(), OffsetDateTime.now(clock)));
    }
}
