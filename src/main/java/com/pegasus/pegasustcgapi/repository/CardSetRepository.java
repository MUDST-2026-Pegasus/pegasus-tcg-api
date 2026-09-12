package com.pegasus.pegasustcgapi.repository;

import static com.pegasus.pegasustcgapi.jooq.tables.CardSet.CARD_SET;

import com.pegasus.pegasustcgapi.jooq.tables.records.CardSetRecord;
import com.pegasus.pegasustcgapi.model.CardSet;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/** Reads and writes {@code card_set} — the releases within one game. */
@Repository
public class CardSetRepository {

    private final DSLContext dsl;

    public CardSetRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** Newest release first, which is the order anyone looking for a set expects. */
    public List<CardSet> findByGameId(short gameId) {
        return dsl.selectFrom(CARD_SET)
                .where(CARD_SET.GAME_ID.eq(gameId))
                .orderBy(CARD_SET.RELEASE_DATE.desc().nullsLast(), CARD_SET.NAME.asc())
                .fetch(CardSetRepository::toCardSet);
    }

    public Optional<CardSet> findById(int id) {
        return dsl.selectFrom(CARD_SET)
                .where(CARD_SET.ID.eq(id))
                .fetchOptional()
                .map(CardSetRepository::toCardSet);
    }

    /** @param exceptId the row being edited, so keeping your own code is not a clash. */
    public boolean codeTaken(short gameId, String code, Integer exceptId) {
        return dsl.fetchExists(dsl.selectOne().from(CARD_SET)
                .where(CARD_SET.GAME_ID.eq(gameId))
                .and(CARD_SET.CODE.equalIgnoreCase(code))
                .and(exceptId == null ? DSL.noCondition() : CARD_SET.ID.ne(exceptId)));
    }

    public int insert(short gameId, CardSetFields fields) {
        return dsl.insertInto(CARD_SET)
                .set(CARD_SET.GAME_ID, gameId)
                .set(CARD_SET.CODE, fields.code())
                .set(CARD_SET.NAME, fields.name())
                .set(CARD_SET.NAME_LOCAL, fields.nameLocal())
                .set(CARD_SET.RELEASE_DATE, fields.releaseDate())
                .set(CARD_SET.TOTAL_CARDS, fields.totalCards())
                .set(CARD_SET.LOGO_URL, fields.logoUrl())
                .returningResult(CARD_SET.ID)
                .fetchSingle(CARD_SET.ID);
    }

    /** The game is fixed at creation: a set does not move between games. */
    public boolean update(int id, CardSetFields fields) {
        return dsl.update(CARD_SET)
                .set(CARD_SET.CODE, fields.code())
                .set(CARD_SET.NAME, fields.name())
                .set(CARD_SET.NAME_LOCAL, fields.nameLocal())
                .set(CARD_SET.RELEASE_DATE, fields.releaseDate())
                .set(CARD_SET.TOTAL_CARDS, fields.totalCards())
                .set(CARD_SET.LOGO_URL, fields.logoUrl())
                .where(CARD_SET.ID.eq(id))
                .execute() > 0;
    }

    private static CardSet toCardSet(CardSetRecord r) {
        return new CardSet(
                r.getId(),
                r.getGameId(),
                r.getCode(),
                r.getName(),
                r.getNameLocal(),
                r.getReleaseDate(),
                r.getTotalCards(),
                r.getLogoUrl());
    }

    public record CardSetFields(
            String code,
            String name,
            String nameLocal,
            java.time.LocalDate releaseDate,
            Integer totalCards,
            String logoUrl) {
    }
}
