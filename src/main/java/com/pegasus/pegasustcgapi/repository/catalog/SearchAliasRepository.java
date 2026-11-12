package com.pegasus.pegasustcgapi.repository.catalog;

import java.time.OffsetDateTime;
import java.util.List;
import org.jooq.DSLContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

/** {@code search_alias} (TCG-773), in plain SQL: a small table read whole on a search miss. */
@Repository
public class SearchAliasRepository {

    private final DSLContext dsl;

    public SearchAliasRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    // shortcut: every alias is read per lookup; cache it if the table grows past a few thousand rows.
    public List<Alias> all() {
        return dsl.fetch("SELECT id, alias, alias_norm, target, game_id, created_at FROM search_alias ORDER BY id")
                .map(SearchAliasRepository::alias);
    }

    /** @param q matched against the alias and the target; null for every row */
    public List<Alias> page(String q, int limit, int offset) {
        String pattern = q == null || q.isBlank() ? null : "%" + SearchText.normalize(q) + "%";
        return dsl.fetch("""
                SELECT id, alias, alias_norm, target, game_id, created_at FROM search_alias
                WHERE CAST(? AS text) IS NULL OR alias_norm LIKE ? OR f_search_norm(target) LIKE ?
                ORDER BY alias_norm, id LIMIT ? OFFSET ?
                """, pattern, pattern, pattern, limit, offset).map(SearchAliasRepository::alias);
    }

    public long count(String q) {
        String pattern = q == null || q.isBlank() ? null : "%" + SearchText.normalize(q) + "%";
        return dsl.fetchSingle("""
                SELECT count(*) FROM search_alias
                WHERE CAST(? AS text) IS NULL OR alias_norm LIKE ? OR f_search_norm(target) LIKE ?
                """, pattern, pattern, pattern).get(0, Long.class);
    }

    /** @throws DuplicateKeyException when the same alias, target and game exist */
    public Alias insert(String alias, String target, Short gameId, long createdBy) {
        return alias(dsl.fetchSingle("""
                INSERT INTO search_alias (alias, target, game_id, created_by) VALUES (?, ?, ?, ?)
                RETURNING id, alias, alias_norm, target, game_id, created_at
                """, alias, target, gameId, createdBy));
    }

    public Alias delete(long id) {
        var row = dsl.fetchOptional("""
                DELETE FROM search_alias WHERE id = ?
                RETURNING id, alias, alias_norm, target, game_id, created_at
                """, id);
        return row.map(SearchAliasRepository::alias).orElse(null);
    }

    private static Alias alias(org.jooq.Record r) {
        return new Alias(r.get("id", Long.class), r.get("alias", String.class), r.get("alias_norm", String.class),
                r.get("target", String.class), r.get("game_id", Short.class),
                r.get("created_at", OffsetDateTime.class));
    }

    /** @param gameId null when the alias applies to every game */
    public record Alias(long id, String alias, String aliasNorm, String target, Short gameId,
            OffsetDateTime createdAt) {
    }
}
