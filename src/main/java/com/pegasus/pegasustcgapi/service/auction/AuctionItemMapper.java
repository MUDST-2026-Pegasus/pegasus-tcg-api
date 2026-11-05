package com.pegasus.pegasustcgapi.service.auction;

import com.pegasus.pegasustcgapi.dto.auction.AuctionViews.AuctionItem;
import com.pegasus.pegasustcgapi.dto.listing.ListingCard;
import com.pegasus.pegasustcgapi.model.catalog.CardCondition;
import com.pegasus.pegasustcgapi.model.catalog.CardSet;
import com.pegasus.pegasustcgapi.model.catalog.CatalogProduct;
import com.pegasus.pegasustcgapi.model.catalog.CatalogVariant;
import com.pegasus.pegasustcgapi.model.catalog.Game;
import com.pegasus.pegasustcgapi.repository.auction.AuctionRepository;
import com.pegasus.pegasustcgapi.repository.catalog.CardSetRepository;
import com.pegasus.pegasustcgapi.repository.catalog.CatalogProductRepository;
import com.pegasus.pegasustcgapi.repository.catalog.CatalogVariantRepository;
import com.pegasus.pegasustcgapi.repository.catalog.GameRepository;
import com.pegasus.pegasustcgapi.service.listing.ListingCardRenderer;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Shared helper/mapper for bulk item mapping and bidder alias formatting [ISSUE-001, ISSUE-004, ISSUE-010].
 */
@Component
public class AuctionItemMapper {

    private final ListingCardRenderer cards;
    private final CatalogVariantRepository variants;
    private final CatalogProductRepository products;
    private final GameRepository games;
    private final CardSetRepository cardSets;
    private final AuctionRepository auctions;

    public AuctionItemMapper(
            ListingCardRenderer cards,
            CatalogVariantRepository variants,
            CatalogProductRepository products,
            GameRepository games,
            CardSetRepository cardSets,
            AuctionRepository auctions) {
        this.cards = cards;
        this.variants = variants;
        this.products = products;
        this.games = games;
        this.cardSets = cardSets;
        this.auctions = auctions;
    }

    public record AuctionItemBundle(
            Map<Long, ListingCard> cards,
            Map<Long, CatalogVariant> variants,
            Map<Long, CatalogProduct> products,
            Map<Short, Game> games,
            Map<Integer, CardSet> cardSets,
            Map<Long, String> shopNames) {

        public AuctionItem buildAuctionItem(long catalogVariantId, CardCondition condition) {
            ListingCard card = cards.get(catalogVariantId);
            CatalogVariant variant = variants.get(catalogVariantId);
            if (variant == null) {
                return new AuctionItem(card, condition, null, null, null, null);
            }
            CatalogProduct product = products.get(variant.catalogProductId());
            if (product == null) {
                return new AuctionItem(card, condition, null, null, null, null);
            }
            Game game = games.get(product.gameId());
            String gameName = game != null ? game.name() : null;
            CardSet cardSet = product.cardSetId() != null ? cardSets.get(product.cardSetId()) : null;
            String setName = cardSet != null ? cardSet.name() : null;
            return new AuctionItem(card, condition, gameName, setName, product.cardNumber(), product.rarityCode());
        }

        public String resolveShopName(long sellerProfileId) {
            return shopNames.getOrDefault(sellerProfileId, "Shop");
        }
    }

    public AuctionItemBundle buildAuctionItems(Collection<Long> catalogVariantIds, Collection<Long> sellerProfileIds) {
        Map<Long, ListingCard> cardMap = new HashMap<>();
        if (catalogVariantIds != null && !catalogVariantIds.isEmpty()) {
            try {
                Map<Long, ListingCard> batch = cards.cards(catalogVariantIds);
                if (batch != null) {
                    cardMap.putAll(batch);
                }
            } catch (Exception ignored) {}
            for (Long vid : catalogVariantIds) {
                if (!cardMap.containsKey(vid)) {
                    try {
                        ListingCard c = cards.card(vid);
                        if (c != null) {
                            cardMap.put(vid, c);
                        }
                    } catch (Exception ignored) {}
                }
            }
        }

        Map<Long, CatalogVariant> variantMap = new HashMap<>();
        if (catalogVariantIds != null && !catalogVariantIds.isEmpty()) {
            try {
                Map<Long, CatalogVariant> batch = variants.findByIds(catalogVariantIds);
                if (batch != null) {
                    variantMap.putAll(batch);
                }
            } catch (Exception ignored) {}
            for (Long vid : catalogVariantIds) {
                if (!variantMap.containsKey(vid)) {
                    try {
                        variants.findById(vid).ifPresent(v -> variantMap.put(vid, v));
                    } catch (Exception ignored) {}
                }
            }
        }

        List<Long> productIds = variantMap.values().stream()
                .map(CatalogVariant::catalogProductId)
                .distinct()
                .toList();

        Map<Long, CatalogProduct> productMap = !productIds.isEmpty()
                ? products.findByIds(productIds)
                : Map.of();

        List<Short> gameIds = productMap.values().stream()
                .map(CatalogProduct::gameId)
                .distinct()
                .toList();

        Map<Short, Game> gameMap = !gameIds.isEmpty()
                ? games.findByIds(gameIds)
                : Map.of();

        List<Integer> setIds = productMap.values().stream()
                .map(CatalogProduct::cardSetId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        Map<Integer, CardSet> setMap = !setIds.isEmpty()
                ? cardSets.findByIds(setIds)
                : Map.of();

        Map<Long, String> shopNameMap = new HashMap<>();
        if (sellerProfileIds != null && !sellerProfileIds.isEmpty()) {
            try {
                Map<Long, String> batch = auctions.findShopNames(sellerProfileIds);
                if (batch != null) {
                    shopNameMap.putAll(batch);
                }
            } catch (Exception ignored) {}
            for (Long sid : sellerProfileIds) {
                if (!shopNameMap.containsKey(sid)) {
                    try {
                        auctions.findSellerInfo(sid).ifPresent(info -> shopNameMap.put(sid, info.shopName()));
                    } catch (Exception ignored) {}
                }
            }
        }

        return new AuctionItemBundle(cardMap, variantMap, productMap, gameMap, setMap, shopNameMap);
    }

    public AuctionItem buildAuctionItem(long catalogVariantId, CardCondition condition) {
        ListingCard card = null;
        try {
            card = cards.card(catalogVariantId);
        } catch (Exception ignored) {}
        if (card == null) {
            try {
                Map<Long, ListingCard> map = cards.cards(List.of(catalogVariantId));
                if (map != null) {
                    card = map.get(catalogVariantId);
                }
            } catch (Exception ignored) {}
        }

        CatalogVariant variant = null;
        try {
            variant = variants.findById(catalogVariantId).orElse(null);
        } catch (Exception ignored) {}
        if (variant == null) {
            try {
                Map<Long, CatalogVariant> map = variants.findByIds(List.of(catalogVariantId));
                if (map != null) {
                    variant = map.get(catalogVariantId);
                }
            } catch (Exception ignored) {}
        }

        if (variant == null) {
            return new AuctionItem(card, condition, null, null, null, null);
        }

        CatalogProduct product = products.findById(variant.catalogProductId()).orElse(null);
        if (product == null) {
            return new AuctionItem(card, condition, null, null, null, null);
        }

        Game game = games.findById(product.gameId()).orElse(null);
        String gameName = game != null ? game.name() : null;
        CardSet cardSet = product.cardSetId() != null ? cardSets.findById(product.cardSetId()).orElse(null) : null;
        String setName = cardSet != null ? cardSet.name() : null;
        return new AuctionItem(card, condition, gameName, setName, product.cardNumber(), product.rarityCode());
    }

    public String resolveShopName(long sellerProfileId) {
        Map<Long, String> names = auctions.findShopNames(List.of(sellerProfileId));
        if (names != null && names.containsKey(sellerProfileId)) {
            return names.get(sellerProfileId);
        }
        return auctions.findSellerInfo(sellerProfileId)
                .map(AuctionRepository.SellerInfo::shopName)
                .orElse("Shop");
    }

    public static String maskUsername(String username) {
        if (username == null || username.isBlank()) {
            return "Anonymous";
        }
        String s = username.strip();
        if (s.length() <= 2) {
            return s.toLowerCase() + "***";
        }
        return (s.charAt(0) + "***" + s.charAt(s.length() - 1)).toLowerCase();
    }

    public static String bidderInitials(String username) {
        if (username == null || username.isBlank()) {
            return "AN";
        }
        String s = username.strip().toUpperCase();
        if (s.length() <= 1) {
            return (s + s).substring(0, 2);
        }
        return "" + s.charAt(0) + s.charAt(s.length() - 1);
    }
}
