package com.pegasus.pegasustcgapi.service;

import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.dto.ProductSummaryResponse;
import com.pegasus.pegasustcgapi.exception.ApiException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.model.AttributeDataType;
import com.pegasus.pegasustcgapi.model.CatalogProduct;
import com.pegasus.pegasustcgapi.model.GameAttribute;
import com.pegasus.pegasustcgapi.model.ProductType;
import com.pegasus.pegasustcgapi.repository.CatalogImageRepository;
import com.pegasus.pegasustcgapi.repository.CatalogProductRepository;
import com.pegasus.pegasustcgapi.repository.CatalogVariantRepository;
import com.pegasus.pegasustcgapi.repository.ProductSearchQuery;
import com.pegasus.pegasustcgapi.storage.StorageService;
import java.math.BigDecimal;
import java.time.LocalDate;
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

    /** Enough for a grid; beyond this a client is pulling the catalogue, not browsing it. */
    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final CatalogProductRepository products;
    private final CatalogVariantRepository variants;
    private final CatalogImageRepository images;
    private final GameService games;
    private final StorageService storage;

    public CatalogSearchService(
            CatalogProductRepository products,
            CatalogVariantRepository variants,
            CatalogImageRepository images,
            GameService games,
            StorageService storage) {

        this.products = products;
        this.variants = variants;
        this.images = images;
        this.games = games;
        this.storage = storage;
    }

    /**
     * @param activeOnly true for the public catalogue. An admin passes false,
     *                   because a product that was deactivated by mistake is
     *                   otherwise findable only by someone who already knows its id
     */
    public PageResponse<ProductSummaryResponse> search(
            Short gameId,
            Integer categoryId,
            Integer cardSetId,
            ProductType productType,
            String nameQuery,
            Map<String, String> rawParameters,
            String sort,
            boolean activeOnly,
            int page,
            int size) {

        ProductSearchQuery query = new ProductSearchQuery(
                gameId, categoryId, cardSetId, productType, nameQuery,
                typedAttributes(gameId, rawParameters),
                activeOnly,
                parseSort(sort),
                Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                Math.max(page, 0) * Math.min(Math.max(size, 1), MAX_PAGE_SIZE));

        List<CatalogProduct> found = products.search(query);
        return PageResponse.of(
                summarise(found),
                Math.max(page, 0),
                query.limit(),
                products.count(query));
    }

    /** One lookup of images and one of variant counts for the whole page, not one per row. */
    private List<ProductSummaryResponse> summarise(List<CatalogProduct> found) {
        List<Long> ids = found.stream().map(CatalogProduct::id).toList();
        Map<Long, String> imageKeys = images.primaryKeysOf(ids);
        Map<Long, Integer> variantCounts = variants.activeCountsOf(ids);

        return found.stream()
                .map(product -> ProductSummaryResponse.of(
                        product,
                        signed(imageKeys.get(product.id())),
                        variantCounts.getOrDefault(product.id(), 0)))
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
    private Map<String, Object> typedAttributes(Short gameId, Map<String, String> rawParameters) {
        Map<String, String> requested = new LinkedHashMap<>();
        rawParameters.forEach((key, value) -> {
            if (key.startsWith(ATTRIBUTE_PREFIX) && value != null && !value.isBlank()) {
                requested.put(key.substring(ATTRIBUTE_PREFIX.length()), value);
            }
        });

        if (requested.isEmpty()) {
            return Map.of();
        }
        if (gameId == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "Filtering by attributes needs a gameId, since attributes are defined per game");
        }

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
            default -> throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "sort must be one of name, newest, cardNumber");
        };
    }
}
