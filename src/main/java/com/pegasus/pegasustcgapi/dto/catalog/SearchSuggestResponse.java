package com.pegasus.pegasustcgapi.dto.catalog;

import java.math.BigDecimal;
import java.util.List;

/** The search box dropdown (TCG-774). */
public record SearchSuggestResponse(
        String query,
        String correctedQuery,
        String correctionReason,
        List<String> completions,
        List<Scope> scopes,
        List<Product> products,
        List<Shop> shops,
        long totalProducts) {

    public static SearchSuggestResponse empty(String query) {
        return new SearchSuggestResponse(query, null, null, List.of(), List.of(), List.of(), List.of(), 0);
    }

    /** @param type GAME or CATEGORY */
    public record Scope(String type, long id, String name, int count) {
    }

    public record Product(long id, String slug, String name, String setName, String cardNumber, String gameName,
            String imageUrl, BigDecimal lowestPrice, int sellerCount) {
    }

    public record Shop(String username, String displayName, String avatarUrl, BigDecimal ratingAvg,
            int ratingCount, int matchCount) {
    }

    public record Trending(String query, int rank) {
    }
}
