package com.pegasus.pegasustcgapi.service.auction;

import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.common.Paging;
import com.pegasus.pegasustcgapi.dto.auction.AdminAuctionViews.AdminAction;
import com.pegasus.pegasustcgapi.dto.auction.AdminAuctionViews.AdminAuctionDetail;
import com.pegasus.pegasustcgapi.dto.auction.AdminAuctionViews.AdminAuctionPage;
import com.pegasus.pegasustcgapi.dto.auction.AdminAuctionViews.AdminAuctionRow;
import com.pegasus.pegasustcgapi.dto.auction.AdminAuctionViews.AdminSeller;
import com.pegasus.pegasustcgapi.dto.auction.AdminAuctionViews.BanTab;
import com.pegasus.pegasustcgapi.dto.auction.AdminAuctionViews.BannedBidder;
import com.pegasus.pegasustcgapi.dto.auction.AdminAuctionViews.BannedBidderPage;
import com.pegasus.pegasustcgapi.dto.auction.AdminAuctionViews.Strike;
import com.pegasus.pegasustcgapi.dto.auction.AdminAuctionViews.Tab;
import com.pegasus.pegasustcgapi.dto.auction.AuctionViews.AuctionItem;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.model.auction.Auction;
import com.pegasus.pegasustcgapi.repository.auction.AdminAuctionRepository;
import com.pegasus.pegasustcgapi.repository.auction.AdminAuctionRepository.BidderSummary;
import com.pegasus.pegasustcgapi.repository.auction.AuctionRepository;
import com.pegasus.pegasustcgapi.service.setting.PlatformSettingService;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Database-backed admin views. Signal and report fields are empty until report storage exists. */
@Service
public class AdminAuctionReadService {
    private final AdminAuctionRepository admin;
    private final AuctionRepository auctions;
    private final AuctionItemMapper items;
    private final PlatformSettingService settings;
    private final Clock clock;

    public AdminAuctionReadService(AdminAuctionRepository admin, AuctionRepository auctions,
            AuctionItemMapper items, PlatformSettingService settings, Clock clock) {
        this.admin = admin;
        this.auctions = auctions;
        this.items = items;
        this.settings = settings;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AdminAuctionPage list(Tab tab, String q, int page, int size) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        Paging paging = Paging.of(page, size);
        Map<Tab, Long> counts = admin.countAuctionTabs(q);
        List<Auction> rows = admin.findAuctions(tab, q, paging.size(), paging.offset());
        Set<Long> sellerIds = rows.stream().map(Auction::sellerProfileId).collect(Collectors.toSet());
        var bundle = items.buildAuctionItems(
                rows.stream().map(Auction::catalogVariantId).collect(Collectors.toSet()), sellerIds);
        Map<Long, String> usernames = admin.sellerUsernames(sellerIds);
        List<AdminAuctionRow> result = rows.stream().map(a -> {
            AuctionItem item = bundle.buildAuctionItem(a.catalogVariantId(), a.condition());
            return new AdminAuctionRow(a.id(), a.auctionNumber(),
                    item.card() == null ? null : item.card().productName(),
                    bundle.resolveShopName(a.sellerProfileId()), usernames.get(a.sellerProfileId()),
                    a.closingOrCurrentPrice(), a.bidCount(), a.bidderCount(), a.status(), a.endsAt(), a.paymentDueAt(),
                    List.of(), 0, a.suspendedAt(),
                    item.card() == null ? null : item.card().officialImageUrl());
        }).toList();
        // Signals and reports stay at zero until report storage and signal rules exist.
        return new AdminAuctionPage(PageResponse.of(result, paging.page(), paging.size(), counts.get(tab)),
                counts, admin.kpis(now), 0, 0, 0, now);
    }

    @Transactional(readOnly = true)
    public AdminAuctionDetail detail(long id) {
        Auction a = auctions.findById(id).orElseThrow(() -> new NotFoundException(ErrorCode.AUCTION_NOT_FOUND));
        var seller = admin.sellerSummary(a.sellerProfileId());
        List<AdminAction> actions = a.suspendedAt() == null ? List.of() : List.of(new AdminAction(
                "SUSPEND", a.suspendedBy() == null ? null : admin.findUsername(a.suspendedBy()).orElse(null),
                a.suspendedReason(), a.suspendedAt()));
        return new AdminAuctionDetail(a.id(), a.auctionNumber(), a.status(),
                items.buildAuctionItem(a.catalogVariantId(), a.condition()),
                new AdminSeller(a.sellerProfileId(), seller.displayName(), seller.username(), seller.verified(),
                        seller.auctionCount(), seller.suspensionCount()),
                a.startingPrice(), a.bidIncrement(), a.closingOrCurrentPrice(),
                a.bidCount(), a.bidderCount(), a.buyNowPrice(), a.originalEndsAt(), a.endsAt(),
                a.extensionCount(), a.sellerNote(), a.sellerNoteUpdatedAt(), List.of(), 0,
                admin.findBids(id), actions, OffsetDateTime.now(clock));
    }

    @Transactional(readOnly = true)
    public BannedBidderPage bannedBidders(BanTab tab, String q, int page, int size) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        Paging paging = Paging.of(page, size);
        int limit = strikeLimit();
        Map<BanTab, Long> counts = admin.countBidderTabs(q, limit, now);
        List<BidderSummary> rows = admin.findBidders(tab, q, limit, now, paging.size(), paging.offset());
        Map<Long, List<Strike>> strikes = admin.findStrikes(rows.stream().map(BidderSummary::userId).toList());
        List<BannedBidder> slice = rows.stream()
                .map(b -> toBannedBidder(b, limit, strikes.getOrDefault(b.userId(), List.of())))
                .toList();
        var stats = admin.banStats(now);
        return new BannedBidderPage(PageResponse.of(slice, paging.page(), paging.size(), counts.get(tab)),
                counts, stats.liftedLast30Days(), stats.expiredLast30Days());
    }

    @Transactional(readOnly = true)
    public BannedBidder bannedBidder(long userId) {
        BidderSummary b = admin.findBidder(userId, OffsetDateTime.now(clock))
                .orElseThrow(() -> new NotFoundException(ErrorCode.USER_NOT_FOUND));
        return toBannedBidder(b, strikeLimit(), admin.findStrikes(List.of(userId)).getOrDefault(userId, List.of()));
    }

    private int strikeLimit() {
        return settings.getInt(PlatformSettingService.AUCTION_STRIKE_LIMIT);
    }

    private static BannedBidder toBannedBidder(BidderSummary b, int strikeLimit, List<Strike> strikes) {
        return new BannedBidder(b.userId(), b.username(), maskEmail(b.email()), b.memberSince(),
                b.strikeCount(), strikeLimit, b.bannedUntil(), b.liftedAt(), strikes);
    }

    static String maskEmail(String email) {
        return email == null ? null : email.replaceFirst("(?<=.).*(?=@)", "***");
    }
}
