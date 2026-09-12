package com.pegasus.pegasustcgapi.repository;

import static com.pegasus.pegasustcgapi.jooq.tables.Game.GAME;

import com.pegasus.pegasustcgapi.jooq.tables.records.GameRecord;
import com.pegasus.pegasustcgapi.model.Game;
import java.util.List;
import java.util.Optional;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/** Reads and writes {@code game}. Games arrive from the back office, never from a migration [RQ-3]. */
@Repository
public class GameRepository {

    private final DSLContext dsl;

    public GameRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** @param includeInactive admins see retired games; buyers do not. */
    public List<Game> findAll(boolean includeInactive) {
        Condition visible = includeInactive ? DSL.noCondition() : GAME.IS_ACTIVE.isTrue();

        return dsl.selectFrom(GAME)
                .where(visible)
                .orderBy(GAME.DISPLAY_ORDER.asc(), GAME.NAME.asc())
                .fetch(GameRepository::toGame);
    }

    public Optional<Game> findById(short id) {
        return dsl.selectFrom(GAME)
                .where(GAME.ID.eq(id))
                .fetchOptional()
                .map(GameRepository::toGame);
    }

    public boolean exists(short id) {
        return dsl.fetchExists(dsl.selectOne().from(GAME).where(GAME.ID.eq(id)));
    }

    /** @param exceptId the row being edited, so keeping your own code is not a clash. */
    public boolean codeTaken(String code, Short exceptId) {
        return dsl.fetchExists(dsl.selectOne().from(GAME)
                .where(GAME.CODE.equalIgnoreCase(code))
                .and(exceptId == null ? DSL.noCondition() : GAME.ID.ne(exceptId)));
    }

    public boolean slugTaken(String slug) {
        return dsl.fetchExists(dsl.selectOne().from(GAME).where(GAME.SLUG.eq(slug)));
    }

    public short insert(GameFields fields, Long createdBy) {
        return dsl.insertInto(GAME)
                .set(GAME.CODE, fields.code())
                .set(GAME.NAME, fields.name())
                .set(GAME.NAME_LOCAL, fields.nameLocal())
                .set(GAME.SLUG, fields.slug())
                .set(GAME.LOGO_URL, fields.logoUrl())
                .set(GAME.DISPLAY_ORDER, fields.displayOrder())
                .set(GAME.IS_ACTIVE, fields.active())
                .set(GAME.CREATED_BY, createdBy)
                .returningResult(GAME.ID)
                .fetchSingle(GAME.ID);
    }

    /**
     * Everything but the slug: links and bookmarks point at that, so it is set
     * once when the game is created and left alone afterwards.
     *
     * @return false when there is no such game
     */
    public boolean update(short id, GameFields fields) {
        return dsl.update(GAME)
                .set(GAME.CODE, fields.code())
                .set(GAME.NAME, fields.name())
                .set(GAME.NAME_LOCAL, fields.nameLocal())
                .set(GAME.LOGO_URL, fields.logoUrl())
                .set(GAME.DISPLAY_ORDER, fields.displayOrder())
                .set(GAME.IS_ACTIVE, fields.active())
                .where(GAME.ID.eq(id))
                .execute() > 0;
    }

    private static Game toGame(GameRecord r) {
        return new Game(
                r.getId(),
                r.getCode(),
                r.getName(),
                r.getNameLocal(),
                r.getSlug(),
                r.getLogoUrl(),
                r.getDisplayOrder(),
                r.getIsActive(),
                r.getCreatedBy(),
                r.getCreatedAt());
    }

    /** What a caller supplies. {@code slug} is read on insert and ignored on update. */
    public record GameFields(
            String code,
            String name,
            String nameLocal,
            String slug,
            String logoUrl,
            short displayOrder,
            boolean active) {
    }
}
