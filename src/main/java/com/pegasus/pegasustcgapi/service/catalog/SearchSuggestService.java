package com.pegasus.pegasustcgapi.service.catalog;

import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.config.AuthProperties;
import com.pegasus.pegasustcgapi.dto.catalog.ProductSummaryResponse;
import com.pegasus.pegasustcgapi.dto.catalog.SearchSuggestResponse;
import com.pegasus.pegasustcgapi.dto.catalog.TrendingProductResponse;
import com.pegasus.pegasustcgapi.exception.ApiException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.model.catalog.Game;
import com.pegasus.pegasustcgapi.repository.catalog.SearchLogRepository;
import com.pegasus.pegasustcgapi.repository.catalog.SearchText;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * The search box (TCG-774): the dropdown, trending searches, and the anonymous
 * log behind them. Matching is {@link CatalogSearchService}'s, so the dropdown
 * finds what the results page would.
 */
@Service
public class SearchSuggestService {

    static final int MAX_QUERY_LENGTH = 60;
    static final int TRENDING_DAYS = 7;
    static final int TRENDING_MIN_SESSIONS = 3;
    static final int LOG_KEEP_DAYS = 90;

    private static final Logger log = LoggerFactory.getLogger(SearchSuggestService.class);

    private final CatalogSearchService search;
    private final SearchLogRepository logs;
    private final GameService games;
    private final AuthProperties auth;
    private final Clock clock;
    private final double fuzzyThreshold;

    public SearchSuggestService(CatalogSearchService search, SearchLogRepository logs, GameService games,
            AuthProperties auth, Clock clock, @Value("${pegasus.search.fuzzy-threshold:0.45}") double fuzzyThreshold) {
        this.search = search;
        this.logs = logs;
        this.games = games;
        this.auth = auth;
        this.clock = clock;
        this.fuzzyThreshold = fuzzyThreshold;
    }

    public SearchSuggestResponse suggest(String q, Short gameId) {
        if (q == null || q.isBlank()) {
            return SearchSuggestResponse.empty(q == null ? "" : q);
        }
        if (q.codePointCount(0, q.length()) > MAX_QUERY_LENGTH) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "q must be at most " + MAX_QUERY_LENGTH + " characters");
        }
        Set<Short> gameIds = gameId == null ? Set.of() : Set.of(gameId);
        // One resolution (alias / typo / keyboard layout) for everything below, and only the counts
        // the dropdown shows: no condition counts, totals or shop previews (TCG-779 latency).
        CatalogSearchService.SuggestData data = search.suggestData(browse(gameIds, q.trim(), false, 4), 4, 2);
        PageResponse<ProductSummaryResponse> page = data.page();
        String effective = page.correctedQuery() == null ? q.trim() : page.correctedQuery();

        Map<Short, String> gameNames = games.list(true).stream().collect(Collectors.toMap(Game::id, Game::name));
        Map<Integer, String> setNames = logs.cardSetNames(page.items().stream()
                .map(ProductSummaryResponse::cardSetId).filter(id -> id != null).distinct().toList());
        List<SearchSuggestResponse.Product> products = page.items().stream()
                .map(p -> new SearchSuggestResponse.Product(p.id(), p.slug(), p.name(),
                        p.cardSetId() == null ? null : setNames.get(p.cardSetId()), p.cardNumber(),
                        gameNames.get(p.gameId()), p.primaryImageUrl(), p.lowestPrice(), p.sellerCount()))
                .toList();

        List<SearchSuggestResponse.Scope> scopes = new ArrayList<>();
        if (gameId == null) {
            data.games().forEach(g -> scopes.add(new SearchSuggestResponse.Scope("GAME", g.id(), g.name(), g.count())));
        }
        data.categories().forEach(c -> scopes.add(new SearchSuggestResponse.Scope("CATEGORY", c.id(), c.name(),
                c.count())));

        List<SearchSuggestResponse.Shop> shops = data.shops().stream()
                .map(s -> new SearchSuggestResponse.Shop(s.username(), s.displayName(),
                        search.signedAvatar(s.avatarKey()), s.ratingAvg(), s.ratingCount(), s.matchCount()))
                .toList();

        // totalProducts is what "view all" opens, so it counts like the results page (sold-out included).
        return new SearchSuggestResponse(q, page.correctedQuery(), page.correctionReason(),
                logs.completions(SearchText.normalize(effective), gameId, fuzzyThreshold, 5),
                scopes.stream().limit(3).toList(), products, shops, data.totalProducts());
    }

    /** Top searches of the last week; when too few people searched, the best-selling product names instead. */
    public List<SearchSuggestResponse.Trending> trending(Integer limit) {
        int places = limit == null ? 5 : limit;
        if (places < 1 || places > 20) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "limit must be between 1 and 20");
        }
        List<String> queries = new ArrayList<>(logs.trending(
                OffsetDateTime.now(clock).minusDays(TRENDING_DAYS), TRENDING_MIN_SESSIONS, places));
        if (queries.size() < places) {
            for (TrendingProductResponse product : search.trending(null, null, places)) {
                if (queries.size() >= places) break;
                String name = product.product().name();
                if (!queries.contains(SearchText.normalize(name))) queries.add(name);
            }
        }
        List<SearchSuggestResponse.Trending> ranked = new ArrayList<>();
        queries.forEach(query -> ranked.add(new SearchSuggestResponse.Trending(query, ranked.size() + 1)));
        return ranked;
    }

    /**
     * One row per first results page of a typed search. Never fails the search.
     *
     * @param client the caller's address and user agent; hashed with the day and a server key, never stored
     */
    public void record(String q, PageResponse<ProductSummaryResponse> result, String client) {
        // shortcut: written inline (one small insert); move to an executor if search latency suffers.
        try {
            String normalized = SearchText.normalize(q);
            if (normalized.isEmpty()) return;
            logs.insert(truncate(normalized), result.totalItems(),
                    result.correctedQuery() == null ? null : truncate(result.correctedQuery()),
                    sessionHash(client), OffsetDateTime.now(clock));
        } catch (RuntimeException failed) {
            log.warn("Search log write failed: {}", failed.getClass().getSimpleName());
        }
    }

    @Scheduled(cron = "${pegasus.search.log-cleanup-cron:0 30 3 * * *}")
    public int deleteOldLogs() {
        return logs.deleteBefore(OffsetDateTime.now(clock).minusDays(LOG_KEEP_DAYS));
    }

    String sessionHash(String client) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(auth.jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((LocalDate.now(clock) + "|" + client).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException | InvalidKeyException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String truncate(String text) {
        return text.length() <= MAX_QUERY_LENGTH ? text : text.substring(0, MAX_QUERY_LENGTH);
    }

    private static ProductBrowse browse(Set<Short> gameIds, String q, boolean inStock, int size) {
        return new ProductBrowse(gameIds, null, null, null, q, Map.of(), null, true, inStock, Set.of(), null, null,
                0, size, null, false);
    }
}
