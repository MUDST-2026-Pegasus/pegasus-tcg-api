package com.pegasus.pegasustcgapi.repository.catalog;

import java.time.OffsetDateTime;
import java.util.List;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** {@code search_query_log} (TCG-774), in plain SQL. */
@Repository
public class SearchLogRepository {

    private final DSLContext dsl;

    public SearchLogRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public void insert(String qNorm, long resultCount, String correctedTo, String sessionHash, OffsetDateTime at) {
        dsl.execute("INSERT INTO search_query_log (q_norm, result_count, corrected_to, session_hash, created_at) "
                + "VALUES (?, ?, ?, ?, CAST(? AS timestamptz))", qNorm, resultCount, correctedTo, sessionHash, at);
    }

    /**
     * Searches that found something, made from at least {@code minSessions} different sessions since
     * {@code since}. Ranked by how many sessions searched it, so one session repeating a search (or paging
     * a results list) cannot push a term up.
     */
    public List<String> trending(OffsetDateTime since, int minSessions, int limit) {
        return dsl.fetch("""
                SELECT q_norm FROM search_query_log
                WHERE created_at >= CAST(? AS timestamptz) AND result_count > 0
                GROUP BY q_norm
                HAVING count(DISTINCT session_hash) >= ?
                ORDER BY count(DISTINCT session_hash) DESC, count(*) DESC, q_norm
                LIMIT ?
                """, since, minSessions, limit).map(r -> r.get(0, String.class));
    }

    public int deleteBefore(OffsetDateTime cutoff) {
        return dsl.execute("DELETE FROM search_query_log WHERE created_at < CAST(? AS timestamptz)", cutoff);
    }

    /**
     * Up to {@code limit} names starting with the query, then names merely close to it:
     * product and set names and alias targets, so the dropdown can finish a word.
     *
     * @param normalized already through {@link SearchText#normalize}
     */
    public List<String> completions(String normalized, Short gameId, double threshold, int limit) {
        String prefix = normalized.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return dsl.fetch("""
                SELECT term FROM (
                    SELECT term, min(rank) AS rank, max(score) AS score FROM (
                        SELECT name AS term, CASE WHEN f_search_norm(name) LIKE ? THEN 0 ELSE 1 END AS rank,
                               word_similarity(?, f_search_norm(name)) AS score
                        FROM catalog_product WHERE is_active AND (CAST(? AS smallint) IS NULL OR game_id = ?)
                        UNION ALL
                        SELECT name, CASE WHEN f_search_norm(name) LIKE ? THEN 0 ELSE 1 END,
                               word_similarity(?, f_search_norm(name))
                        FROM card_set WHERE CAST(? AS smallint) IS NULL OR game_id = ?
                        UNION ALL
                        SELECT target, CASE WHEN f_search_norm(target) LIKE ? THEN 0 ELSE 1 END,
                               word_similarity(?, f_search_norm(target))
                        FROM search_alias WHERE CAST(? AS smallint) IS NULL OR game_id IS NULL OR game_id = ?
                    ) candidates
                    WHERE rank = 0 OR score >= ?
                    GROUP BY term
                ) terms
                ORDER BY rank, score DESC, length(term), term
                LIMIT ?
                """, prefix, normalized, gameId, gameId, prefix, normalized, gameId, gameId,
                prefix, normalized, gameId, gameId, threshold, limit).map(r -> r.get(0, String.class));
    }

    public java.util.Map<Integer, String> cardSetNames(java.util.Collection<Integer> ids) {
        if (ids.isEmpty()) {
            return java.util.Map.of();
        }
        java.util.Map<Integer, String> names = new java.util.HashMap<>();
        dsl.fetch("SELECT id, name FROM card_set WHERE id = ANY(?)", (Object) ids.toArray(Integer[]::new))
                .forEach(r -> names.put(r.get(0, Integer.class), r.get(1, String.class)));
        return names;
    }
}
