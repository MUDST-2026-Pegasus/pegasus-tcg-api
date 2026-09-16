package com.pegasus.pegasustcgapi.repository;

import static com.pegasus.pegasustcgapi.jooq.tables.CatalogCategory.CATALOG_CATEGORY;

import com.pegasus.pegasustcgapi.jooq.tables.records.CatalogCategoryRecord;
import com.pegasus.pegasustcgapi.model.CatalogCategory;
import java.util.List;
import java.util.Optional;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/** Reads and writes {@code catalog_category}. A category with no game applies to every game. */
@Repository
public class CatalogCategoryRepository {

    private final DSLContext dsl;

    public CatalogCategoryRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * @param gameId filters to one game; the cross-game categories come with it,
     *               since Accessory belongs on every game's shelf. Null returns all.
     */
    public List<CatalogCategory> find(Short gameId, boolean includeInactive) {
        Condition ofGame = gameId == null
                ? DSL.noCondition()
                : CATALOG_CATEGORY.GAME_ID.eq(gameId).or(CATALOG_CATEGORY.GAME_ID.isNull());

        Condition visible = includeInactive ? DSL.noCondition() : CATALOG_CATEGORY.IS_ACTIVE.isTrue();

        return dsl.selectFrom(CATALOG_CATEGORY)
                .where(ofGame)
                .and(visible)
                .orderBy(CATALOG_CATEGORY.DISPLAY_ORDER.asc(), CATALOG_CATEGORY.NAME.asc())
                .fetch(CatalogCategoryRepository::toCategory);
    }

    public Optional<CatalogCategory> findById(int id) {
        return dsl.selectFrom(CATALOG_CATEGORY)
                .where(CATALOG_CATEGORY.ID.eq(id))
                .fetchOptional()
                .map(CatalogCategoryRepository::toCategory);
    }

    /**
     * Mirrors {@code ux_catalog_category_code}, which is NULLS NOT DISTINCT: two
     * cross-game categories cannot share a code either.
     *
     * @param exceptId the row being edited, so keeping your own code is not a clash
     */
    public boolean codeTaken(Short gameId, String code, Integer exceptId) {
        Condition sameGame = gameId == null
                ? CATALOG_CATEGORY.GAME_ID.isNull()
                : CATALOG_CATEGORY.GAME_ID.eq(gameId);

        return dsl.fetchExists(dsl.selectOne().from(CATALOG_CATEGORY)
                .where(sameGame)
                .and(CATALOG_CATEGORY.CODE.equalIgnoreCase(code))
                .and(exceptId == null ? DSL.noCondition() : CATALOG_CATEGORY.ID.ne(exceptId)));
    }

    public int insert(CategoryFields fields) {
        return dsl.insertInto(CATALOG_CATEGORY)
                .set(CATALOG_CATEGORY.GAME_ID, fields.gameId())
                .set(CATALOG_CATEGORY.PARENT_ID, fields.parentId())
                .set(CATALOG_CATEGORY.CODE, fields.code())
                .set(CATALOG_CATEGORY.NAME, fields.name())
                .set(CATALOG_CATEGORY.SLUG, fields.slug())
                .set(CATALOG_CATEGORY.DISPLAY_ORDER, fields.displayOrder())
                .set(CATALOG_CATEGORY.IS_ACTIVE, fields.active())
                .returningResult(CATALOG_CATEGORY.ID)
                .fetchSingle(CATALOG_CATEGORY.ID);
    }

    /** The game a category belongs to is fixed at creation; moving it would strand its products. */
    public boolean update(int id, CategoryFields fields) {
        return dsl.update(CATALOG_CATEGORY)
                .set(CATALOG_CATEGORY.PARENT_ID, fields.parentId())
                .set(CATALOG_CATEGORY.CODE, fields.code())
                .set(CATALOG_CATEGORY.NAME, fields.name())
                .set(CATALOG_CATEGORY.SLUG, fields.slug())
                .set(CATALOG_CATEGORY.DISPLAY_ORDER, fields.displayOrder())
                .set(CATALOG_CATEGORY.IS_ACTIVE, fields.active())
                .where(CATALOG_CATEGORY.ID.eq(id))
                .execute() > 0;
    }

    private static CatalogCategory toCategory(CatalogCategoryRecord r) {
        return new CatalogCategory(
                r.getId(),
                r.getGameId(),
                r.getParentId(),
                r.getCode(),
                r.getName(),
                r.getSlug(),
                r.getDisplayOrder(),
                r.getIsActive());
    }

    /** @param gameId null for a category that spans every game. */
    public record CategoryFields(
            Short gameId,
            Integer parentId,
            String code,
            String name,
            String slug,
            short displayOrder,
            boolean active) {
    }
}
