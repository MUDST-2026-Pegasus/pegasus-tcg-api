package com.pegasus.pegasustcgapi.controller.auction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.pegasus.pegasustcgapi.dto.auction.AdminAuctionViews.AdminAuctionDetail;
import com.pegasus.pegasustcgapi.dto.auction.AdminAuctionViews.BannedBidder;
import com.pegasus.pegasustcgapi.dto.auction.AuctionRequests.LiftBiddingBan;
import com.pegasus.pegasustcgapi.dto.auction.AuctionRequests.SuspendAuction;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.service.auction.AdminAuctionReadService;
import com.pegasus.pegasustcgapi.service.auction.AdminAuctionService;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AdminAuctionControllerTest {
    private final AdminAuctionService actions = mock(AdminAuctionService.class);
    private final AdminAuctionReadService reads = mock(AdminAuctionReadService.class);
    private final AdminAuctionController controller = new AdminAuctionController(actions, reads);
    private final AuthPrincipal admin = new AuthPrincipal(7L, "admin@example.test", "admin", Set.of());

    @Test
    void suspendUpdatesDatabaseBeforeReturningFreshDetail() {
        AdminAuctionDetail detail = mock(AdminAuctionDetail.class);
        when(reads.detail(42L)).thenReturn(detail);

        assertThat(controller.suspend(42L, new SuspendAuction("Rule violation"), admin).getData())
                .isSameAs(detail);
        var order = inOrder(actions, reads);
        order.verify(actions).suspend(42L, 7L, "Rule violation");
        order.verify(reads).detail(42L);
    }

    @Test
    void liftingBanUpdatesDatabaseBeforeReturningFreshBidder() {
        BannedBidder bidder = mock(BannedBidder.class);
        when(reads.bannedBidder(12L)).thenReturn(bidder);

        assertThat(controller.liftBan(12L, new LiftBiddingBan("Payment outage"), admin).getData())
                .isSameAs(bidder);
        var order = inOrder(actions, reads);
        order.verify(actions).liftBan(12L, 7L, "Payment outage");
        order.verify(reads).bannedBidder(12L);
    }
}
