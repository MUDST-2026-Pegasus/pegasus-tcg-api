package com.pegasus.pegasustcgapi.repository.home;

import java.math.BigDecimal;
import java.util.List;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** Two database round trips regardless of the number of eligible shops. */
@Repository
public class RecommendedShopRepository {

    private final DSLContext db;

    public RecommendedShopRepository(DSLContext db) {
        this.db = db;
    }

    public List<Shop> topShops() {
        return db.fetch("""
                SELECT sp.id AS seller_id, u.username, u.display_name, u.avatar_url,
                       COALESCE(r.rating_avg, 0) AS rating_avg,
                       COALESCE(r.rating_count, 0) AS rating_count,
                       COUNT(l.id)::int AS active_listing_count
                FROM seller_profile sp
                JOIN user_account u ON u.id = sp.user_id
                JOIN listing l ON l.seller_profile_id = sp.id
                    AND l.status = 'ACTIVE' AND l.deleted_at IS NULL
                LEFT JOIN seller_rating_summary r ON r.seller_profile_id = sp.id
                WHERE sp.status = 'VERIFIED'
                  AND u.status = 'ACTIVE' AND u.deleted_at IS NULL
                  AND (COALESCE(r.rating_count, 0) < 3 OR r.rating_avg >= 3.5)
                GROUP BY sp.id, u.id, r.rating_avg, r.rating_count, r.rating_sum
                ORDER BY (COALESCE(r.rating_sum, 0) + 20)::numeric
                         / (COALESCE(r.rating_count, 0) + 5) DESC,
                         active_listing_count DESC, sp.id ASC
                LIMIT 8
                """).map(row -> new Shop(
                row.get("seller_id", Long.class),
                row.get("username", String.class),
                row.get("display_name", String.class),
                row.get("avatar_url", String.class),
                row.get("rating_avg", BigDecimal.class),
                row.get("rating_count", Integer.class),
                row.get("active_listing_count", Integer.class)));
    }

    public List<Preview> previews(List<Long> sellerIds) {
        if (sellerIds.isEmpty()) return List.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(sellerIds.size(), "?"));
        return db.fetch("""
                WITH ranked AS (
                    SELECT l.seller_profile_id, l.id AS listing_id, l.catalog_variant_id,
                           ROW_NUMBER() OVER (PARTITION BY l.seller_profile_id
                                              ORDER BY l.created_at DESC, l.id DESC) AS position
                    FROM listing l
                    WHERE l.seller_profile_id IN (%s)
                      AND l.status = 'ACTIVE' AND l.deleted_at IS NULL
                )
                SELECT ranked.seller_profile_id, ranked.listing_id,
                       COALESCE(photo.image_key, art.image_key) AS image_key
                FROM ranked
                JOIN catalog_variant v ON v.id = ranked.catalog_variant_id
                LEFT JOIN LATERAL (
                    SELECT li.image_key FROM listing_image li
                    WHERE li.listing_id = ranked.listing_id
                    ORDER BY li.is_primary DESC, li.sort_order ASC, li.id ASC LIMIT 1
                ) photo ON TRUE
                LEFT JOIN LATERAL (
                    SELECT ci.image_key FROM catalog_image ci
                    WHERE ci.catalog_product_id = v.catalog_product_id
                    ORDER BY ci.is_primary DESC, ci.sort_order ASC, ci.id ASC LIMIT 1
                ) art ON TRUE
                WHERE ranked.position <= 3
                ORDER BY ranked.seller_profile_id, ranked.position
                """.formatted(placeholders), sellerIds.toArray()).map(row -> new Preview(
                row.get("seller_profile_id", Long.class),
                row.get("listing_id", Long.class),
                row.get("image_key", String.class)));
    }

    public record Shop(long sellerId, String username, String displayName, String avatarKey,
                       BigDecimal ratingAvg, int ratingCount, int activeListingCount) {}

    public record Preview(long sellerId, long listingId, String imageKey) {}
}
