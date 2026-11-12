package com.pegasus.pegasustcgapi.service.catalog;

import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.common.Paging;
import com.pegasus.pegasustcgapi.dto.catalog.ProductSummaryResponse;
import com.pegasus.pegasustcgapi.dto.catalog.TrendingProductResponse;
import com.pegasus.pegasustcgapi.exception.ApiException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.model.catalog.AttributeDataType;
import com.pegasus.pegasustcgapi.model.catalog.CardCondition;
import com.pegasus.pegasustcgapi.model.catalog.CatalogProduct;
import com.pegasus.pegasustcgapi.model.catalog.GameAttribute;
import com.pegasus.pegasustcgapi.repository.catalog.CatalogImageRepository;
import com.pegasus.pegasustcgapi.repository.catalog.CatalogProductRepository;
import com.pegasus.pegasustcgapi.repository.catalog.CatalogVariantRepository;
import com.pegasus.pegasustcgapi.repository.pricing.ProductMarketRepository;
import com.pegasus.pegasustcgapi.repository.pricing.ProductMarketRepository.Offer;
import com.pegasus.pegasustcgapi.repository.pricing.ProductMarketRepository.TrendingRow;
import com.pegasus.pegasustcgapi.repository.catalog.ProductSearchQuery;
import com.pegasus.pegasustcgapi.repository.catalog.SearchAliasRepository;
import com.pegasus.pegasustcgapi.repository.catalog.SearchText;
import com.pegasus.pegasustcgapi.storage.StorageService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Browsing the catalogue.
 *
 * <p>Most of this class is about turning strings into something a database index
 * can use. A query string has no types — {@code attr.hp=200} arrives as text —
 * but the jsonb containment test that makes the filter fast compares JSON types
 * as well as values, so {@code {"hp":"200"}} would quietly match nothing. The
 * game's own attribute registry is what says which it should be.
 */
@Service
public class CatalogSearchService {

    /** Query parameters shaped {@code attr.<key>=<value>} are attribute filters. */
    public static final String ATTRIBUTE_PREFIX = "attr.";

    static final int DEFAULT_TRENDING_DAYS = 30;
    static final int MAX_TRENDING_DAYS = 365;
    static final int DEFAULT_TRENDING_LIMIT = 10;
    static final int MAX_TRENDING_LIMIT = 50;

    private final CatalogProductRepository products;
    private final CatalogVariantRepository variants;
    private final CatalogImageRepository images;
    private final ProductMarketRepository market;
    private final GameService games;
    private final StorageService storage;
    private final Clock clock;
    private SearchAliasRepository aliases;

    public CatalogSearchService(
            CatalogProductRepository products,
            CatalogVariantRepository variants,
            CatalogImageRepository images,
            ProductMarketRepository market,
            GameService games,
            StorageService storage,
            Clock clock) {

        this.products = products;
        this.variants = variants;
        this.images = images;
        this.market = market;
        this.games = games;
        this.storage = storage;
        this.clock = clock;
    }

    @org.springframework.beans.factory.annotation.Autowired
    void setAliases(SearchAliasRepository aliases) {
        this.aliases = aliases;
    }

    /**
     * One page of the catalogue. {@code activeOnly} is true for the public
     * catalogue; an admin passes false, because a product that was deactivated by
     * mistake is otherwise findable only by someone who already knows its id.
     *
     * <p>Asking for conditions narrows the prices as well as the products: a tile
     * found by "NM under 500" shows the cheapest NM listing, not a cheaper LP one.
     */
    public PageResponse<ProductSummaryResponse> search(ProductBrowse browse) {
        requirePriceRange(browse.minPrice(), browse.maxPrice());

        String requested = browse.nameQuery();
        boolean tolerant = browse.activeOnly() && requested != null && !requested.isBlank();
        PageResponse<ProductSummaryResponse> found = searchAs(browse, requested, tolerant);
        if (!tolerant || products.count(query(browse, requested, false)) > 0) {
            return found;
        }

        // Nothing contains the text as typed: try a known alias (โปเกมอน → Pokemon), then it spelled right,
        // then typed on the other keyboard layout.
        PageResponse<ProductSummaryResponse> corrected = viaAlias(browse, requested.trim());
        if (corrected != null) {
            return corrected;
        }
        corrected = firstHit(browse, corrections(requested.trim()), "TYPO");
        if (corrected != null) {
            return corrected;
        }
        String switched = SearchText.switchKeyboard(requested);
        if (found.totalItems() == 0 && switched != null) {
            List<String> tries = new ArrayList<>(List.of(switched));
            tries.addAll(corrections(switched));
            corrected = firstHit(browse, tries, "KEYBOARD_LAYOUT");
            if (corrected != null) {
                return corrected;
            }
        }
        return found;
    }

    /**
     * The whole query, or else each word, swapped for the target of an alias it
     * matches (same normalising, or one edit away from four letters on). An alias
     * tied to a game only applies within that game, and narrows an all-games search to it.
     */
    private PageResponse<ProductSummaryResponse> viaAlias(ProductBrowse browse, String requested) {
        if (aliases == null) {
            return null;
        }
        List<SearchAliasRepository.Alias> usable = aliases.all().stream()
                .filter(a -> a.gameId() == null || browse.gameIds().isEmpty() || browse.gameIds().contains(a.gameId()))
                .toList();
        if (usable.isEmpty()) {
            return null;
        }
        List<SearchAliasRepository.Alias> used = new ArrayList<>();
        String phrase = SearchText.normalize(requested);
        SearchAliasRepository.Alias whole = closestAlias(phrase, usable);
        String expanded;
        if (whole != null) {
            used.add(whole);
            expanded = whole.target();
        } else {
            StringBuilder words = new StringBuilder();
            for (String word : requested.split("\\s+")) {
                SearchAliasRepository.Alias match = closestAlias(SearchText.normalize(word), usable);
                if (match != null) {
                    used.add(match);
                }
                words.append(words.isEmpty() ? "" : " ").append(match == null ? word : match.target());
            }
            expanded = words.toString();
        }
        if (used.isEmpty()) {
            return null;
        }
        Set<Short> games = browse.gameIds();
        if (games.isEmpty()) {
            Set<Short> tied = new java.util.HashSet<>();
            used.forEach(a -> { if (a.gameId() != null) tied.add(a.gameId()); });
            games = tied;
        }
        ProductBrowse narrowed = new ProductBrowse(games, browse.categoryId(), browse.cardSetId(),
                browse.productType(), browse.nameQuery(), browse.rawParameters(), browse.sort(), browse.activeOnly(),
                browse.inStockOnly(), browse.conditions(), browse.minPrice(), browse.maxPrice(), browse.page(),
                browse.size(), browse.saleStatus());
        if (products.count(query(narrowed, expanded, true)) == 0) {
            return null;
        }
        return searchAs(narrowed, expanded, true).withCorrectedQuery(expanded, "ALIAS");
    }

    private static SearchAliasRepository.Alias closestAlias(String normalized, List<SearchAliasRepository.Alias> all) {
        SearchAliasRepository.Alias best = null;
        int bestDistance = 2;
        for (SearchAliasRepository.Alias alias : all) {
            int limit = normalized.codePointCount(0, normalized.length()) >= 4 ? 1 : 0;
            int distance = editDistance(normalized, alias.aliasNorm(), limit);
            if (distance <= limit && distance < bestDistance) {
                best = alias;
                bestDistance = distance;
            }
        }
        return best;
    }

    private PageResponse<ProductSummaryResponse> firstHit(ProductBrowse browse, List<String> tries, String reason) {
        for (String attempt : tries) {
            if (products.count(query(browse, attempt, false)) > 0) {
                return searchAs(browse, attempt, true).withCorrectedQuery(attempt, reason);
            }
        }
        return null;
    }

    /**
     * Each word of four or more letters that no searchable name contains is swapped
     * for the closest one (1 edit under five letters, 2 from five); the rest stay as
     * typed, so "charzard ex" becomes "charizard ex". Up to three variants, best first.
     */
    private List<String> corrections(String requested) {
        String[] words = requested.split("\\s+");
        List<List<String>> options = new ArrayList<>();
        boolean any = false;
        for (String word : words) {
            List<String> closest = List.of();
            if (word.codePointCount(0, word.length()) >= 4) {
                List<String> candidates = products.correctionCandidates(word);
                String normalized = SearchText.normalize(word);
                boolean known = candidates.stream().flatMap(c -> List.of(c.split("\\s+")).stream())
                        .anyMatch(term -> SearchText.normalize(term).equals(normalized));
                if (!known) {
                    closest = closestTerms(normalized, candidates);
                }
            }
            any |= !closest.isEmpty();
            options.add(closest);
        }
        if (!any) {
            return List.of();
        }
        List<String> variants = new ArrayList<>();
        for (int rank = 0; rank < 3; rank++) {
            StringBuilder variant = new StringBuilder();
            for (int i = 0; i < words.length; i++) {
                List<String> closest = options.get(i);
                variant.append(i == 0 ? "" : " ")
                        .append(closest.isEmpty() ? words[i] : closest.get(Math.min(rank, closest.size() - 1)));
            }
            if (!variants.contains(variant.toString())) {
                variants.add(variant.toString());
            }
        }
        return variants;
    }

    private PageResponse<ProductSummaryResponse> searchAs(ProductBrowse browse, String nameQuery, boolean fuzzy) {
        Paging paging = Paging.of(browse.page(), browse.size());
        ProductSearchQuery query = query(browse, nameQuery, fuzzy);
        List<CatalogProduct> found = products.search(query);
        return PageResponse.of(summarise(found, browse.conditions()), paging.page(), paging.size(),
                products.count(query));
    }

    private ProductSearchQuery query(ProductBrowse browse, String nameQuery, boolean fuzzy) {
        Paging paging = Paging.of(browse.page(), browse.size());
        return new ProductSearchQuery(
                browse.gameIds(), browse.categoryId(), browse.cardSetId(), browse.productType(),
                nameQuery,
                typedAttributes(browse.gameIds(), browse.rawParameters()),
                browse.activeOnly(),
                browse.inStockOnly(),
                browse.conditions(),
                browse.minPrice(),
                browse.maxPrice(),
                parseSort(browse.sort() == null && nameQuery != null && !nameQuery.isBlank()
                        ? "relevance" : browse.sort()),
                paging.size(),
                paging.offset(),
                OffsetDateTime.now(clock),
                browse.saleStatus(),
                fuzzy);
    }

    /** @param query already normalised; candidates and their words are compared normalised too */
    private static List<String> closestTerms(String query, List<String> candidates) {
        int maxDistance = query.codePointCount(0, query.length()) < 5 ? 1 : 2;
        Map<String, Integer> distances = new HashMap<>();
        for (String candidate : candidates) {
            for (String term : candidate.split("\\s+")) {
                int distance = editDistance(query, SearchText.normalize(term), maxDistance);
                if (distance > 0 && distance <= maxDistance) distances.merge(term, distance, Math::min);
            }
            int distance = editDistance(query, SearchText.normalize(candidate), maxDistance);
            if (distance > 0 && distance <= maxDistance) distances.merge(candidate, distance, Math::min);
        }
        return distances.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
                        .thenComparingInt(entry -> entry.getKey().length())
                        .thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getKey)
                .toList();
    }

    /** Adjacent swaps count as one typo, alongside insertion, deletion and replacement. */
    private static int editDistance(String left, String right, int limit) {
        if (Math.abs(left.length() - right.length()) > limit) {
            return limit + 1;
        }
        int[][] distance = new int[left.length() + 1][right.length() + 1];
        for (int i = 0; i <= left.length(); i++) distance[i][0] = i;
        for (int j = 0; j <= right.length(); j++) distance[0][j] = j;
        for (int i = 1; i <= left.length(); i++) {
            for (int j = 1; j <= right.length(); j++) {
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                distance[i][j] = Math.min(distance[i - 1][j - 1] + cost,
                        Math.min(distance[i - 1][j] + 1, distance[i][j - 1] + 1));
                if (i > 1 && j > 1 && left.charAt(i - 1) == right.charAt(j - 2)
                        && left.charAt(i - 2) == right.charAt(j - 1)) {
                    distance[i][j] = Math.min(distance[i][j], distance[i - 2][j - 2] + 1);
                }
            }
        }
        return distance[left.length()][right.length()];
    }

    private static void requirePriceRange(BigDecimal minPrice, BigDecimal maxPrice) {
        if ((minPrice != null && minPrice.signum() < 0) || (maxPrice != null && maxPrice.signum() < 0)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "minPrice and maxPrice cannot be negative");
        }
        if (minPrice != null && maxPrice != null && minPrice.compareTo(maxPrice) > 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "minPrice cannot be above maxPrice");
        }
    }

    /**
     * What has been selling, for the home page rail. Only cards on sale now make
     * the list, since a tile nobody can buy is a dead end.
     *
     * @param gameId null ranks every game together
     * @param days   how far back sales count; 30 when left out
     * @param limit  how many places; 10 when left out
     */
    public List<TrendingProductResponse> trending(Short gameId, Integer days, Integer limit) {
        int window = days == null ? DEFAULT_TRENDING_DAYS : days;
        int places = limit == null ? DEFAULT_TRENDING_LIMIT : limit;
        if (window < 1 || window > MAX_TRENDING_DAYS) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "days must be between 1 and " + MAX_TRENDING_DAYS);
        }
        if (places < 1 || places > MAX_TRENDING_LIMIT) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "limit must be between 1 and " + MAX_TRENDING_LIMIT);
        }
        if (gameId != null) {
            games.requireActive(gameId);
        }

        List<TrendingRow> ranked = market.trending(
                gameId, OffsetDateTime.now(clock).minusDays(window), places, OffsetDateTime.now(clock));

        Map<Long, CatalogProduct> byId = products.findByIds(ranked.stream().map(TrendingRow::productId).toList());
        List<CatalogProduct> inOrder = ranked.stream()
                .map(row -> byId.get(row.productId()))
                .filter(product -> product != null)
                .toList();
        Map<Long, ProductSummaryResponse> summaries = new LinkedHashMap<>();
        summarise(inOrder, Set.of()).forEach(summary -> summaries.put(summary.id(), summary));

        List<TrendingProductResponse> rail = new ArrayList<>();
        for (TrendingRow row : ranked) {
            ProductSummaryResponse summary = summaries.get(row.productId());
            if (summary != null) {
                rail.add(new TrendingProductResponse(rail.size() + 1, row.unitsSold(), summary));
            }
        }
        return rail;
    }

    /**
     * One lookup each of images, variant counts and prices for the whole page, not one per row.
     *
     * @param conditions the prices shown come from listings in these; empty for any
     */
    private List<ProductSummaryResponse> summarise(List<CatalogProduct> found, Set<CardCondition> conditions) {
        List<Long> ids = found.stream().map(CatalogProduct::id).toList();
        Map<Long, String> imageKeys = images.primaryKeysOf(ids);
        Map<Long, Integer> variantCounts = variants.activeCountsOf(ids);
        Map<Long, Offer> offers = market.offersOf(ids, conditions, OffsetDateTime.now(clock));

        return found.stream()
                .map(product -> {
                    Offer offer = offers.get(product.id());
                    return ProductSummaryResponse.of(
                            product,
                            signed(imageKeys.get(product.id())),
                            variantCounts.getOrDefault(product.id(), 0),
                            offer == null ? null : offer.lowestPrice(),
                            offer == null ? 0 : offer.listingCount());
                })
                .toList();
    }

    private String signed(String imageKey) {
        return imageKey == null ? null : storage.presignDownload(imageKey);
    }

    /**
     * Reads the {@code attr.*} parameters and gives each value the JSON type its
     * attribute is declared as.
     *
     * <p>An unknown key is refused rather than ignored: a silently dropped filter
     * looks like a filter that found everything, which is worse than an error.
     */
    private Map<String, Object> typedAttributes(Set<Short> gameIds, Map<String, String> rawParameters) {
        Map<String, String> requested = new LinkedHashMap<>();
        rawParameters.forEach((key, value) -> {
            if (key.startsWith(ATTRIBUTE_PREFIX) && value != null && !value.isBlank()) {
                requested.put(key.substring(ATTRIBUTE_PREFIX.length()), value);
            }
        });

        if (requested.isEmpty()) {
            return Map.of();
        }
        if (gameIds.size() != 1) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "Filtering by attributes needs exactly one gameId, since attributes are defined per game");
        }
        short gameId = gameIds.iterator().next();

        Map<String, GameAttribute> declared = new LinkedHashMap<>();
        games.attributesOf(gameId).forEach(attribute -> declared.put(attribute.attrKey(), attribute));

        List<String> problems = new ArrayList<>();
        Map<String, Object> typed = new LinkedHashMap<>();

        requested.forEach((key, value) -> {
            GameAttribute attribute = declared.get(key);
            if (attribute == null) {
                problems.add(key + " is not an attribute of this game");
                return;
            }
            try {
                typed.put(key, typedValue(attribute, value));
            } catch (IllegalArgumentException badValue) {
                problems.add(badValue.getMessage());
            }
        });

        if (!problems.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_PRODUCT_ATTRIBUTES, String.join("; ", problems));
        }
        return typed;
    }

    private static Object typedValue(GameAttribute attribute, String value) {
        AttributeDataType type = attribute.dataType();
        String key = attribute.attrKey();

        return switch (type) {
            case NUMBER -> parseNumber(key, value);
            case BOOLEAN -> parseBoolean(key, value);
            case ENUM -> requireOption(attribute, value);
            case DATE -> requireDate(key, value);
            case STRING -> value;
        };
    }

    private static Object parseNumber(String key, String value) {
        try {
            BigDecimal number = new BigDecimal(value);
            // A whole number has to travel as one: jsonb sees 200 and 200.0 as different.
            return number.stripTrailingZeros().scale() <= 0 ? number.longValueExact() : number;
        } catch (ArithmeticException | NumberFormatException notANumber) {
            throw new IllegalArgumentException(key + " must be a number");
        }
    }

    private static Object parseBoolean(String key, String value) {
        String folded = value.toLowerCase(Locale.ROOT);
        if (!Set.of("true", "false").contains(folded)) {
            throw new IllegalArgumentException(key + " must be true or false");
        }
        return Boolean.parseBoolean(folded);
    }

    private static Object requireOption(GameAttribute attribute, String value) {
        if (!attribute.options().contains(value)) {
            throw new IllegalArgumentException(
                    attribute.attrKey() + " must be one of " + attribute.options());
        }
        return value;
    }

    private static Object requireDate(String key, String value) {
        try {
            LocalDate.parse(value);
            return value;
        } catch (DateTimeParseException notADate) {
            throw new IllegalArgumentException(key + " must be a date as yyyy-MM-dd");
        }
    }

    private static ProductSearchQuery.Sort parseSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return ProductSearchQuery.Sort.NAME;
        }
        return switch (sort.trim().toLowerCase(Locale.ROOT)) {
            case "name" -> ProductSearchQuery.Sort.NAME;
            case "newest" -> ProductSearchQuery.Sort.NEWEST;
            case "cardnumber", "card_number" -> ProductSearchQuery.Sort.CARD_NUMBER;
            case "price", "price_asc" -> ProductSearchQuery.Sort.PRICE_ASC;
            case "price_desc" -> ProductSearchQuery.Sort.PRICE_DESC;
            case "popular" -> ProductSearchQuery.Sort.POPULAR;
            case "relevance" -> ProductSearchQuery.Sort.RELEVANCE;
            default -> throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "sort must be one of relevance, name, newest, cardNumber, price_asc, price_desc, popular");
        };
    }
}
