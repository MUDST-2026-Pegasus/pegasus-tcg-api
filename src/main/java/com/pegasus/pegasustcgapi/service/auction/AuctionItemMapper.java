package com.pegasus.pegasustcgapi.service.auction;

import com.pegasus.pegasustcgapi.dto.auction.AuctionViews.AuctionItem;
import com.pegasus.pegasustcgapi.dto.listing.ListingCard;
import com.pegasus.pegasustcgapi.model.catalog.CardCondition;
import com.pegasus.pegasustcgapi.model.catalog.CardSet;
import com.pegasus.pegasustcgapi.model.catalog.CatalogProduct;
import com.pegasus.pegasustcgapi.model.catalog.CatalogVariant;
import com.pegasus.pegasustcgapi.model.catalog.Game;
import com.pegasus.pegasustcgapi.repository.auction.AuctionReferenceQueryRepository;
import com.pegasus.pegasustcgapi.repository.catalog.CardSetRepository;
import com.pegasus.pegasustcgapi.repository.catalog.CatalogProductRepository;
import com.pegasus.pegasustcgapi.repository.catalog.CatalogVariantRepository;
import com.pegasus.pegasustcgapi.repository.catalog.GameRepository;
import com.pegasus.pegasustcgapi.service.listing.ListingCardRenderer;
import java.util.Collection;
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
    private final AuctionReferenceQueryRepository auctionReferences;

    public AuctionItemMapper(
            ListingCardRenderer cards,
            CatalogVariantRepository variants,
            CatalogProductRepository products,
            GameRepository games,
            CardSetRepository cardSets,
            AuctionReferenceQueryRepository auctionReferences) {
        this.cards = cards;
        this.variants = variants;
        this.products = products;
        this.games = games;
        this.cardSets = cardSets;
        this.auctionReferences = auctionReferences;
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

    /**
     * Loads everything a page of auction cards needs in a fixed number of queries. A failed
     * query is not caught: it reaches GlobalExceptionHandler instead of leaving the page blank.
     */
    public AuctionItemBundle buildAuctionItems(Collection<Long> catalogVariantIds, Collection<Long> sellerProfileIds) {
        boolean hasVariants = catalogVariantIds != null && !catalogVariantIds.isEmpty();
        Map<Long, ListingCard> cardMap = hasVariants ? cards.cards(catalogVariantIds) : Map.of();
        Map<Long, CatalogVariant> variantMap = hasVariants ? variants.findByIds(catalogVariantIds) : Map.of();

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

        Map<Long, String> shopNameMap = sellerProfileIds != null && !sellerProfileIds.isEmpty()
                ? auctionReferences.findShopNames(sellerProfileIds)
                : Map.of();

        return new AuctionItemBundle(cardMap, variantMap, productMap, gameMap, setMap, shopNameMap);
    }

    public AuctionItem buildAuctionItem(long catalogVariantId, CardCondition condition) {
        ListingCard card = cards.card(catalogVariantId);
        CatalogVariant variant = variants.findById(catalogVariantId).orElse(null);
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
        return auctionReferences.findShopNames(List.of(sellerProfileId)).getOrDefault(sellerProfileId, "Shop");
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
