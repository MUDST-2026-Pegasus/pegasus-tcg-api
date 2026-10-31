package com.pegasus.pegasustcgapi.controller;

import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.dto.AdminAuctionViews;
import com.pegasus.pegasustcgapi.dto.AdminAuctionViews.AdminAction;
import com.pegasus.pegasustcgapi.dto.AdminAuctionViews.AdminAuctionDetail;
import com.pegasus.pegasustcgapi.dto.AdminAuctionViews.AdminAuctionPage;
import com.pegasus.pegasustcgapi.dto.AdminAuctionViews.AdminAuctionRow;
import com.pegasus.pegasustcgapi.dto.AdminAuctionViews.AdminBid;
import com.pegasus.pegasustcgapi.dto.AdminAuctionViews.AdminSeller;
import com.pegasus.pegasustcgapi.dto.AdminAuctionViews.BanTab;
import com.pegasus.pegasustcgapi.dto.AdminAuctionViews.BannedBidder;
import com.pegasus.pegasustcgapi.dto.AdminAuctionViews.BannedBidderPage;
import com.pegasus.pegasustcgapi.dto.AdminAuctionViews.Signal;
import com.pegasus.pegasustcgapi.dto.AdminAuctionViews.SignalDetail;
import com.pegasus.pegasustcgapi.dto.AdminAuctionViews.Strike;
import com.pegasus.pegasustcgapi.dto.AuctionViews.AuctionDetail;
import com.pegasus.pegasustcgapi.dto.AuctionViews.AuctionItem;
import com.pegasus.pegasustcgapi.dto.AuctionViews.AuctionPage;
import com.pegasus.pegasustcgapi.dto.AuctionViews.AuctionSummary;
import com.pegasus.pegasustcgapi.dto.AuctionViews.AuctionWin;
import com.pegasus.pegasustcgapi.dto.AuctionViews.Bid;
import com.pegasus.pegasustcgapi.dto.AuctionViews.BidHistory;
import com.pegasus.pegasustcgapi.dto.AuctionViews.BidPlaced;
import com.pegasus.pegasustcgapi.dto.AuctionViews.Filter;
import com.pegasus.pegasustcgapi.dto.AuctionViews.MyAuction;
import com.pegasus.pegasustcgapi.dto.AuctionViews.MyAuctionPage;
import com.pegasus.pegasustcgapi.dto.AuctionViews.MyTab;
import com.pegasus.pegasustcgapi.dto.AuctionViews.SecondChanceOffer;
import com.pegasus.pegasustcgapi.dto.AuctionViews.Seller;
import com.pegasus.pegasustcgapi.dto.AuctionViews.Viewer;
import com.pegasus.pegasustcgapi.dto.CheckoutResponse;
import com.pegasus.pegasustcgapi.dto.ListingCard;
import com.pegasus.pegasustcgapi.dto.SellerAuctionViews.EligibleUnit;
import com.pegasus.pegasustcgapi.dto.SellerAuctionViews.EligibleUnitPage;
import com.pegasus.pegasustcgapi.dto.SellerAuctionViews.Kpis;
import com.pegasus.pegasustcgapi.dto.SellerAuctionViews.MarketGuide;
import com.pegasus.pegasustcgapi.dto.SellerAuctionViews.PendingDecision;
import com.pegasus.pegasustcgapi.dto.SellerAuctionViews.SecondBidder;
import com.pegasus.pegasustcgapi.dto.SellerAuctionViews.SellerAuctionDetail;
import com.pegasus.pegasustcgapi.dto.SellerAuctionViews.SellerAuctionPage;
import com.pegasus.pegasustcgapi.dto.SellerAuctionViews.SellerAuctionRow;
import com.pegasus.pegasustcgapi.dto.SellerAuctionViews.SentOffer;
import com.pegasus.pegasustcgapi.dto.SellerAuctionViews.UnitAvailability;
import com.pegasus.pegasustcgapi.model.AuctionOfferStatus;
import com.pegasus.pegasustcgapi.model.AuctionStatus;
import com.pegasus.pegasustcgapi.model.AuctionViewerState;
import com.pegasus.pegasustcgapi.model.BidStatus;
import com.pegasus.pegasustcgapi.model.CardCondition;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Fixed example data behind the auction endpoints while only the contract exists [TCG-613].
 * The numbers match the Figma frames so the front end can build against them.
 *
 * <p>Temporary: BE-2 to BE-11 replace each call with a real service, and the last of
 * them deletes this class. Nothing here touches the database.
 */
final class AuctionSamples {

    static final BigDecimal SHIPPING = thb(50);
    static final BigDecimal COMMISSION_PERCENT = thb(5);
    static final int STRIKE_LIMIT = 3;

    private AuctionSamples() {
    }

    // ---- shared pieces ------------------------------------------------------------------

    private static BigDecimal thb(long amount) {
        return BigDecimal.valueOf(amount);
    }

    private static AuctionItem item(long variantId, String name, String slug, CardCondition condition,
            String setName, String cardNumber, String rarity) {
        ListingCard card = new ListingCard(variantId, "PKM-" + variantId, condition.name(), true,
                variantId, name, slug, (short) 1, null);
        return new AuctionItem(card, condition, "Pokémon TCG", setName, cardNumber, rarity);
    }

    static final AuctionItem CHARIZARD = item(1020, "Charizard VMAX #020", "charizard-vmax-020", CardCondition.NM,
            "Sword & Shield — Darkness Ablaze", "020/189", "Ultra Rare");
    static final AuctionItem PIKACHU = item(1188, "Pikachu VMAX — Rainbow", "pikachu-vmax-rainbow", CardCondition.NM,
            "Vivid Voltage", "188/185", "Rainbow Rare");
    static final AuctionItem UMBREON = item(1215, "Umbreon VMAX Alt Art", "umbreon-vmax-alt-art", CardCondition.LP,
            "Evolving Skies", "215/203", "Secret Rare");
    static final AuctionItem RAYQUAZA = item(1218, "Rayquaza VMAX Alt Art", "rayquaza-vmax-alt-art", CardCondition.NM,
            "Evolving Skies", "218/203", "Secret Rare");
    static final AuctionItem LUGIA = item(1186, "Lugia V Alt Art #186", "lugia-v-alt-art-186", CardCondition.NM,
            "Silver Tempest", "186/195", "Ultra Rare");
    static final AuctionItem ESPEON = item(1270, "Espeon VMAX Alt Art", "espeon-vmax-alt-art", CardCondition.NM,
            "Evolving Skies", "270/203", "Secret Rare");
    static final AuctionItem MEW = item(1232, "Mew ex SIR #232", "mew-ex-sir-232", CardCondition.NM,
            "Paldean Fates", "232/091", "Special Illustration Rare");
    static final AuctionItem CHARIZARD_EX = item(1125, "Charizard ex — Obsidian Flames", "charizard-ex-obsidian-flames",
            CardCondition.NM, "Obsidian Flames", "125/197", "Double Rare");

    static final Seller CARDVAULT = new Seller(11, "CardVault BKK", "cardvault_bkk", new BigDecimal("4.9"), 1200);
    static final Seller RARECARD = new Seller(12, "RareCard Station", "rarecard_station", new BigDecimal("4.8"), 860);

    static List<Bid> charizardBids(OffsetDateTime now) {
        return List.of(
                new Bid("n***t", "NS", true, thb(8450), now.minusMinutes(2)),
                new Bid("m***a", "MK", false, thb(8300), now.minusMinutes(4)),
                new Bid("t***n", "TP", false, thb(8000), now.minusMinutes(9)),
                new Bid("n***t", "NS", true, thb(7500), now.minusHours(1)),
                new Bid("p***m", "PP", false, thb(6800), now.minusHours(3)));
    }

    private static <K extends Enum<K>> Map<K, Long> counts(Class<K> type, long... values) {
        Map<K, Long> map = new EnumMap<>(type);
        K[] keys = type.getEnumConstants();
        for (int i = 0; i < keys.length && i < values.length; i++) {
            map.put(keys[i], values[i]);
        }
        return map;
    }

    // ---- public and buyer ---------------------------------------------------------------

    static AuctionPage auctionPage(OffsetDateTime now, int page, int size) {
        List<AuctionSummary> items = List.of(
                new AuctionSummary(142, "AUC-2026-0142", CHARIZARD, CARDVAULT.shopName(), thb(2000), thb(8450), 12,
                        null, now.plusDays(1).plusHours(4)),
                new AuctionSummary(150, "AUC-2026-0150", PIKACHU, CARDVAULT.shopName(), thb(2400), thb(2400), 0,
                        thb(3500), now.plusDays(3).plusHours(4)),
                new AuctionSummary(151, "AUC-2026-0151", UMBREON, RARECARD.shopName(), thb(5000), thb(15200), 27,
                        null, now.plusSeconds(102)),
                new AuctionSummary(139, "AUC-2026-0139", RAYQUAZA, RARECARD.shopName(), thb(5000), thb(21500), 41,
                        null, now.plusMinutes(2)));
        return new AuctionPage(PageResponse.of(items, page, size, 128),
                counts(Filter.class, 14, 32, 41, 9), 128, now);
    }

    static AuctionDetail auctionDetail(long id, OffsetDateTime now, boolean signedIn) {
        Viewer viewer = signedIn
                ? new Viewer(AuctionViewerState.LEADING, thb(8450), false, List.of(), null, null, null)
                : null;
        return new AuctionDetail(id, "AUC-2026-0142", AuctionStatus.LIVE, CHARIZARD,
                List.of(), CARDVAULT,
                "เก็บใน top loader ตั้งแต่แกะซอง ไม่เคยเล่น มีรูปมุมทั้ง 4 ด้านให้ดูในแกลเลอรี · ส่งแบบกล่องแข็ง",
                thb(2000), thb(100), thb(8450), thb(8550), 12, 7, thb(12000), false,
                now.minusDays(3), now.plusDays(1), now.plusDays(1).plusHours(4), 3, SHIPPING,
                charizardBids(now), viewer, now);
    }

    static BidHistory bidHistory(OffsetDateTime now, int page, int size) {
        return new BidHistory(PageResponse.of(charizardBids(now), page, size, 12), now);
    }

    static BidPlaced bidPlaced(long id, BigDecimal amount, OffsetDateTime now) {
        return new BidPlaced(id, amount, true, false, amount, amount.add(thb(100)), 13,
                now.plusDays(1).plusHours(4), now);
    }

    static AuctionWin win(long id, BigDecimal price, OffsetDateTime now) {
        return new AuctionWin(id, "AUC-2026-0150", price, SHIPPING, price.add(SHIPPING),
                now.plusHours(24), now);
    }

    static CheckoutResponse checkout(OffsetDateTime now) {
        return new CheckoutResponse(10412, "ORD-10412", 7, "PENDING_PAYMENT", "THB",
                thb(8450), SHIPPING, BigDecimal.ZERO, thb(8500), now, List.of());
    }

    static MyAuctionPage myAuctions(OffsetDateTime now, int page, int size) {
        List<MyAuction> items = List.of(
                new MyAuction(142, "AUC-2026-0142", CHARIZARD, CARDVAULT.shopName(), 12,
                        AuctionViewerState.WON_AWAITING_PAYMENT, thb(8450), thb(8450), SHIPPING, thb(8500), null,
                        false, now.minusMinutes(20), now.plusHours(23).plusMinutes(40), null),
                new MyAuction(128, "AUC-2026-0128", MEW, "PokeHub Thailand", 14,
                        AuctionViewerState.SECOND_CHANCE, thb(4800), thb(4650), SHIPPING, thb(4700), null,
                        false, now.minusDays(2), now.plusHours(20), null),
                new MyAuction(151, "AUC-2026-0151", UMBREON, RARECARD.shopName(), 27,
                        AuctionViewerState.LEADING, thb(15200), thb(15200), SHIPPING, null, thb(15300),
                        false, now.plusDays(1), null, null),
                new MyAuction(139, "AUC-2026-0139", RAYQUAZA, RARECARD.shopName(), 41,
                        AuctionViewerState.OUTBID, thb(21500), thb(21000), SHIPPING, null, thb(21600),
                        true, now.plusMinutes(2), null, null),
                new MyAuction(117, "AUC-2026-0117", LUGIA, CARDVAULT.shopName(), 9,
                        AuctionViewerState.LOST, thb(6100), thb(5800), SHIPPING, null, null,
                        false, now.minusDays(4), null, null));
        return new MyAuctionPage(PageResponse.of(items, page, size, 15),
                counts(MyTab.class, 15, 3, 1, 1, 4, 6), 0, STRIKE_LIMIT, null, now);
    }

    // ---- seller -------------------------------------------------------------------------

    static SellerAuctionPage sellerAuctions(OffsetDateTime now, int page, int size) {
        List<SellerAuctionRow> rows = List.of(
                new SellerAuctionRow(142, "AUC-2026-0142", CHARIZARD, "U-0771", thb(2000), thb(8450), 12,
                        thb(12000), false, AuctionStatus.LIVE, now.plusSeconds(102), null, null),
                new SellerAuctionRow(150, "AUC-2026-0150", PIKACHU, "U-0988", thb(2400), thb(2400), 0,
                        thb(3500), true, AuctionStatus.LIVE, now.plusDays(3), null, null),
                new SellerAuctionRow(117, "AUC-2026-0117", LUGIA, "U-0912", thb(3000), thb(6100), 9,
                        null, false, AuctionStatus.AWAITING_PAYMENT, now.minusHours(6), now.plusHours(18), 10377L),
                new SellerAuctionRow(119, "AUC-2026-0119", ESPEON, "U-0655", thb(2500), thb(7900), 11,
                        null, false, AuctionStatus.AWAITING_SELLER_DECISION, now.minusDays(2), now.plusHours(47),
                        null),
                new SellerAuctionRow(128, "AUC-2026-0128", MEW, "U-0702", thb(1500), thb(4800), 14,
                        null, false, AuctionStatus.OFFERED_TO_SECOND, now.minusDays(2), now.plusHours(20), null));
        Map<AuctionStatus, Long> statusCounts = counts(AuctionStatus.class, 4, 1, 1, 1, 1, 1, 1, 0);
        Kpis kpis = new Kpis(4, 2, 1, now.plusHours(18), thb(24600), 6, new BigDecimal("12.0"));
        return new SellerAuctionPage(PageResponse.of(rows, page, size, 10), statusCounts, kpis, now);
    }

    static EligibleUnitPage eligibleUnits(int page, int size) {
        MarketGuide guide = new MarketGuide(12, thb(890), thb(1180), thb(1650));
        List<EligibleUnit> units = List.of(
                new EligibleUnit(UUID.fromString("0f9e1c2a-6b1d-4e8a-9a51-2c7d5b3e1042"), "U-1042", CHARIZARD_EX,
                        thb(720), guide, UnitAvailability.AVAILABLE, null),
                new EligibleUnit(UUID.fromString("5a0c7e11-2d4f-4b8e-8f36-91c2d7a40988"), "U-0988", PIKACHU,
                        thb(1690), new MarketGuide(8, thb(2100), thb(2450), thb(2900)),
                        UnitAvailability.LISTED, null),
                new EligibleUnit(UUID.fromString("9b3d2f70-1c6a-4e2b-a7d8-3f5e6c180870"), "U-0870", UMBREON,
                        null, null, UnitAvailability.IN_AUCTION, "#AUC-2026-0151"),
                new EligibleUnit(UUID.fromString("c41e8a2b-7f90-4d3c-b615-0a9e2d7c0912"), "U-0912", LUGIA,
                        null, null, UnitAvailability.RESERVED, "#ORD-10377"));
        return new EligibleUnitPage(PageResponse.of(units, page, size, 5), 37);
    }

    static SellerAuctionDetail sellerDetail(long id, OffsetDateTime now) {
        if (id == 119) {
            PendingDecision decision = new PendingDecision("k***a", thb(7900), "ORD-10351",
                    now.plusHours(47).plusMinutes(12), new SecondBidder("m***a", thb(7600), thb(7220)));
            return new SellerAuctionDetail(119, "AUC-2026-0119", AuctionStatus.AWAITING_SELLER_DECISION, ESPEON,
                    "U-0655", thb(2500), thb(100), thb(7900), 11, 5, thb(7505), COMMISSION_PERCENT, null, false,
                    "เก็บใน top loader ตั้งแต่แกะซอง ไม่เคยเล่น · ส่งแบบกล่องแข็ง", false, false,
                    now.minusDays(5), now.minusDays(2), now.minusDays(2), 0, now.minusDays(5).plusMinutes(20),
                    "k***a", null, decision, null, null, List.of(), now);
        }
        return new SellerAuctionDetail(id, "AUC-2026-0142", AuctionStatus.LIVE, CHARIZARD, "U-0771",
                thb(2000), thb(100), thb(8450), 12, 7, thb(8027), COMMISSION_PERCENT, thb(12000), false,
                "เก็บใน top loader ตั้งแต่แกะซอง ไม่เคยเล่น มีรูปมุมทั้ง 4 ด้านให้ดูในแกลเลอรี · ส่งแบบกล่องแข็ง",
                false, false, now.minusDays(3), now.plusDays(1), now.plusDays(1).plusHours(4), 3,
                now.minusDays(3).plusMinutes(40), null, null, null, null, null,
                charizardBids(now).stream().map(b -> new Bid(b.bidderAlias(), b.bidderInitials(), false,
                        b.amount(), b.placedAt())).toList(),
                now);
    }

    static SellerAuctionDetail created(long id, OffsetDateTime now, BigDecimal start, BigDecimal increment,
            OffsetDateTime endsAt, BigDecimal buyNow, String note) {
        return new SellerAuctionDetail(id, "AUC-2026-0160", AuctionStatus.LIVE, CHARIZARD_EX, "U-1042",
                start, increment, start, 0, 0, start.multiply(new BigDecimal("0.95")), COMMISSION_PERCENT, buyNow,
                buyNow != null, note, true, true, now, endsAt, endsAt, 0, null, null, null, null, null, null,
                List.of(), now);
    }

    static SellerAuctionDetail withStatus(SellerAuctionDetail d, AuctionStatus status, SentOffer offer,
            OffsetDateTime now) {
        return new SellerAuctionDetail(d.id(), d.auctionNumber(), status, d.item(), d.unitLabel(),
                d.startingPrice(), d.bidIncrement(), d.currentPrice(), d.bidCount(), d.bidderCount(),
                d.estimatedPayout(), d.commissionPercent(), d.buyNowPrice(), false, d.sellerNote(), false, false,
                d.startedAt(), d.originalEndsAt(), d.endsAt(), d.extensionCount(), d.firstBidAt(), d.winnerAlias(),
                null, null, offer, d.orderId(), d.bids(), now);
    }

    static SentOffer sentOffer(OffsetDateTime now) {
        return new SentOffer("m***a", thb(7600), AuctionOfferStatus.PENDING, now.plusHours(24));
    }

    static SecondChanceOffer secondChanceOffer(OffsetDateTime now) {
        return new SecondChanceOffer(thb(8300), SHIPPING, thb(8350), AuctionOfferStatus.PENDING,
                now.plusHours(23).plusMinutes(59));
    }

    // ---- admin --------------------------------------------------------------------------

    static AdminAuctionPage adminAuctions(OffsetDateTime now, int page, int size) {
        List<AdminAuctionRow> rows = List.of(
                new AdminAuctionRow(139, "AUC-2026-0139", RAYQUAZA.card().productName(), RARECARD.shopName(),
                        RARECARD.username(), thb(21500), 41, 9, AuctionStatus.LIVE, now.plusMinutes(2), null,
                        List.of(Signal.NEW_ACCOUNT_RAPID_BIDS, Signal.SHARED_ADDRESS_WITH_SELLER), 0, null),
                new AdminAuctionRow(142, "AUC-2026-0142", CHARIZARD.card().productName(), CARDVAULT.shopName(),
                        CARDVAULT.username(), thb(8450), 12, 7, AuctionStatus.AWAITING_PAYMENT, now.minusMinutes(20),
                        now.plusHours(23).plusMinutes(41), List.of(), 0, null),
                new AdminAuctionRow(155, "AUC-2026-0155", "Pikachu Illustrator", "newshop_99", "newshop_99",
                        thb(2000), 0, 0, AuctionStatus.LIVE, now.plusDays(2), null, List.of(Signal.REPORTED), 3,
                        null),
                new AdminAuctionRow(98, "AUC-2026-0098", "Gengar VMAX Alt Art", "fakecards_th", "fakecards_th",
                        thb(6000), 3, 2, AuctionStatus.SUSPENDED, now.minusDays(6), null, List.of(), 0,
                        now.minusDays(6)));
        AdminAuctionViews.Kpis kpis = new AdminAuctionViews.Kpis(128, 14, 9, 3, 2);
        return new AdminAuctionPage(PageResponse.of(rows, page, size, 1204),
                counts(AdminAuctionViews.Tab.class, 1204, 2, 128, 12, 1050, 2), kpis, 2, now);
    }

    static AdminAuctionDetail adminDetail(long id, OffsetDateTime now, List<AdminAction> actions,
            AuctionStatus status) {
        List<AdminBid> bids = List.of(
                new AdminBid(9001, 301, "tonx_bkk", now.minusDays(2), 0, thb(21500), now.minusSeconds(19),
                        BidStatus.ACTIVE, List.of(Signal.NEW_ACCOUNT_RAPID_BIDS)),
                new AdminBid(9000, 7, "somchai.j", now.minusYears(1), 23, thb(21400), now.minusSeconds(58),
                        BidStatus.ACTIVE, List.of()),
                new AdminBid(8999, 302, "rayy.collect", now.minusMonths(8), 4, thb(20500), now.minusMinutes(18),
                        BidStatus.ACTIVE, List.of(Signal.SHARED_ADDRESS_WITH_SELLER)));
        List<SignalDetail> signals = List.of(
                new SignalDetail(Signal.NEW_ACCOUNT_RAPID_BIDS,
                        "บัญชี tonx_bkk สมัครเมื่อ 2 วันก่อน และบิดในประมูลนี้ 14 ครั้ง (34% ของบิดทั้งหมด)"),
                new SignalDetail(Signal.SHARED_ADDRESS_WITH_SELLER,
                        "ผู้บิด rayy.collect ใช้ที่อยู่จัดส่งเดียวกับผู้ขาย RareCard Station"));
        AdminSeller seller = new AdminSeller(RARECARD.sellerProfileId(), RARECARD.shopName(), RARECARD.username(),
                true, 23, 0);
        return new AdminAuctionDetail(id, "AUC-2026-0139", status, RAYQUAZA, seller, thb(5000), thb(100),
                thb(21500), 41, 9, null, now.minusHours(1), now.plusMinutes(2), 7,
                "เก็บใน top loader ตั้งแต่แกะซอง ไม่เคยเล่น · ส่งแบบกล่องแข็ง", now.minusDays(3), signals, 0, bids,
                actions, now);
    }

    static BannedBidder bannedBidder(OffsetDateTime now) {
        return new BannedBidder(401, "kanok.a", "k***a@gmail.com", now.minusMonths(6), 3, STRIKE_LIMIT,
                now.plus(Duration.ofDays(29)), null,
                List.of(new Strike(121, "AUC-2026-0121", now.minusDays(1)),
                        new Strike(102, "AUC-2026-0102", now.minusDays(18)),
                        new Strike(90, "AUC-2026-0090", now.minusDays(28))));
    }

    static BannedBidderPage bannedBidders(OffsetDateTime now, int page, int size) {
        List<BannedBidder> rows = List.of(
                bannedBidder(now),
                new BannedBidder(402, "bid_master99", "b***9@hotmail.com", now.minusMonths(1), 3, STRIKE_LIMIT,
                        now.plusDays(26), null, List.of(new Strike(114, "AUC-2026-0114", now.minusDays(4)))),
                new BannedBidder(403, "pon.tcg", "p***g@gmail.com", now.minusYears(2), 3, STRIKE_LIMIT,
                        now.plusDays(20), null, List.of(new Strike(103, "AUC-2026-0103", now.minusDays(10)))));
        return new BannedBidderPage(PageResponse.of(rows, page, size, 5), counts(BanTab.class, 5, 12, 41), 1, 4);
    }
}
