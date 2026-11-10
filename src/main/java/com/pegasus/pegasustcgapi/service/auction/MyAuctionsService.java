package com.pegasus.pegasustcgapi.service.auction;

import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.common.Paging;
import com.pegasus.pegasustcgapi.dto.auction.AuctionViews.MyAuction;
import com.pegasus.pegasustcgapi.dto.auction.AuctionViews.MyAuctionPage;
import com.pegasus.pegasustcgapi.dto.auction.AuctionViews.MyTab;
import com.pegasus.pegasustcgapi.model.auction.Auction;
import com.pegasus.pegasustcgapi.model.auction.AuctionStatus;
import com.pegasus.pegasustcgapi.model.auction.AuctionViewerState;
import com.pegasus.pegasustcgapi.repository.auction.BidderStrikeRepository;
import com.pegasus.pegasustcgapi.repository.auction.MyAuctionQueryRepository;
import com.pegasus.pegasustcgapi.repository.shipping.ShippingOptionRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The signed-in buyer's auction history [TCG-623]. */
@Service
public class MyAuctionsService {
    private final BidderStrikeRepository bidderStrikes;
    private final MyAuctionQueryRepository myAuctionQueries;
    private final AuctionItemMapper itemMapper;
    private final BidderStrikeService strikes;
    private final ShippingOptionRepository shippingOptions;
    private final Clock clock;

    public MyAuctionsService(BidderStrikeRepository bidderStrikes, MyAuctionQueryRepository myAuctionQueries, AuctionItemMapper itemMapper,
            BidderStrikeService strikes, ShippingOptionRepository shippingOptions, Clock clock) {
        this.bidderStrikes = bidderStrikes;
        this.myAuctionQueries = myAuctionQueries;
        this.itemMapper = itemMapper;
        this.strikes = strikes;
        this.shippingOptions = shippingOptions;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public MyAuctionPage list(long userId, MyTab tab, int page, int size) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        Paging paging = Paging.of(page, size);
        Map<MyTab, Long> counts = myAuctionQueries.countMyAuctionTabs(userId);

        List<Auction> rows = myAuctionQueries.findMyAuctions(userId, tab, paging.size(), paging.offset());
        Set<Long> ids = rows.stream().map(Auction::id).collect(Collectors.toSet());
        Map<Long, BigDecimal> myBids = myAuctionQueries.findMyHighestBids(userId, ids);
        Set<Long> leadingIds = myAuctionQueries.findMyLeadingAuctionIds(userId, ids);
        var items = itemMapper.buildAuctionItems(
                rows.stream().map(Auction::catalogVariantId).collect(Collectors.toSet()),
                rows.stream().map(Auction::sellerProfileId).collect(Collectors.toSet()));
        var defaultShipping = shippingOptions.findDefaultActiveBySellerIds(
                rows.stream().map(Auction::sellerProfileId).collect(Collectors.toSet()));

        List<MyAuction> result = rows.stream().map(a -> {
            AuctionViewerState state = stateOf(a, userId, leadingIds.contains(a.id()));
            boolean live = a.status() == AuctionStatus.LIVE;
            BigDecimal price = a.closingOrCurrentPrice();
            var option = defaultShipping.get(a.sellerProfileId());
            BigDecimal shipping = option == null ? null : option.feeFor(1, price);
            BigDecimal totalDue = state == AuctionViewerState.WON_AWAITING_PAYMENT && shipping != null
                    ? price.add(shipping) : null;
            return new MyAuction(a.id(), a.auctionNumber(),
                    items.buildAuctionItem(a.catalogVariantId(), a.condition()),
                    items.resolveShopName(a.sellerProfileId()), a.bidCount(), state,
                    price, myBids.getOrDefault(a.id(), Objects.equals(a.winnerId(), userId)
                            ? a.winningAmount() : null), shipping, totalDue,
                    live ? a.minNextBid() : null, live && a.extensionCount() > 0,
                    a.closedOrEndsAt(),
                    state == AuctionViewerState.WON_AWAITING_PAYMENT ? a.paymentDueAt() : null,
                    a.salesOrderId());
        }).toList();
        var penalty = strikes.status(userId, now);
        String lastStrikeCardName = penalty.strikeCount() > 0
                ? bidderStrikes.findLastStrikeCardName(userId).orElse(null) : null;
        return new MyAuctionPage(PageResponse.of(result, paging.page(), paging.size(), counts.get(tab)),
                counts, penalty.strikeCount(), penalty.strikeLimit(), penalty.bannedUntil(), lastStrikeCardName, now);
    }

    static AuctionViewerState stateOf(Auction a, long userId, boolean leading) {
        if (a.status() == AuctionStatus.LIVE) {
            return leading ? AuctionViewerState.LEADING : AuctionViewerState.OUTBID;
        }
        if (Objects.equals(a.winnerId(), userId)) {
            if (a.status() == AuctionStatus.AWAITING_PAYMENT) return AuctionViewerState.WON_AWAITING_PAYMENT;
            if (a.status() == AuctionStatus.SOLD) return AuctionViewerState.WON;
        }
        return AuctionViewerState.LOST;
    }
}
