package com.pegasus.pegasustcgapi.service;

import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.common.Paging;
import com.pegasus.pegasustcgapi.dto.ProductSummaryResponse;
import com.pegasus.pegasustcgapi.dto.TrendingProductResponse;
import com.pegasus.pegasustcgapi.exception.ApiException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.model.AttributeDataType;
import com.pegasus.pegasustcgapi.model.CardCondition;
import com.pegasus.pegasustcgapi.model.CatalogProduct;
import com.pegasus.pegasustcgapi.model.GameAttribute;
import com.pegasus.pegasustcgapi.repository.CatalogImageRepository;
import com.pegasus.pegasustcgapi.repository.CatalogProductRepository;
import com.pegasus.pegasustcgapi.repository.CatalogVariantRepository;
import com.pegasus.pegasustcgapi.repository.ProductMarketRepository;
import com.pegasus.pegasustcgapi.repository.ProductMarketRepository.Offer;
import com.pegasus.pegasustcgapi.repository.ProductMarketRepository.TrendingRow;
import com.pegasus.pegasustcgapi.repository.ProductSearchQuery;
import com.pegasus.pegasustcgapi.storage.StorageService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
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

        Paging paging = Paging.of(browse.page(), browse.size());
        ProductSearchQuery query = new ProductSearchQuery(
                browse.gameIds(), browse.categoryId(), browse.cardSetId(), browse.productType(),
                browse.nameQuery(),
                typedAttributes(browse.gameIds(), browse.rawParameters()),
                browse.activeOnly(),
                browse.inStockOnly(),
                browse.conditions(),
                browse.minPrice(),
                browse.maxPrice(),
                parseSort(browse.sort()),
                paging.size(),
                paging.offset());

        List<CatalogProduct> found = products.search(query);
        return PageResponse.of(summarise(found, browse.conditions()), paging.page(), paging.size(),
                products.count(query));
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
                gameId, OffsetDateTime.now(clock).minusDays(window), places);

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
        Map<Long, Offer> offers = market.offersOf(ids, conditions);

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
            default -> throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "sort must be one of name, newest, cardNumber, price_asc, price_desc, popular");
        };
    }
}
