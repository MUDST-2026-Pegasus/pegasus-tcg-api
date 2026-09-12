package com.pegasus.pegasustcgapi.repository;

import static com.pegasus.pegasustcgapi.jooq.tables.GameAttribute.GAME_ATTRIBUTE;

import com.pegasus.pegasustcgapi.jooq.tables.records.GameAttributeRecord;
import com.pegasus.pegasustcgapi.model.AttributeDataType;
import com.pegasus.pegasustcgapi.model.GameAttribute;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads and writes {@code game_attribute}, the per-game field registry.
 *
 * <p>{@code options} is JSONB in the database and a list in Java. The conversion
 * happens here, so nothing above this class ever handles raw JSON.
 */
@Repository
public class GameAttributeRepository {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final DSLContext dsl;
    private final ObjectMapper json;

    public GameAttributeRepository(DSLContext dsl, ObjectMapper json) {
        this.dsl = dsl;
        this.json = json;
    }

    public List<GameAttribute> findByGameId(short gameId) {
        return dsl.selectFrom(GAME_ATTRIBUTE)
                .where(GAME_ATTRIBUTE.GAME_ID.eq(gameId))
                .orderBy(GAME_ATTRIBUTE.DISPLAY_ORDER.asc(), GAME_ATTRIBUTE.ATTR_KEY.asc())
                .fetch(this::toAttribute);
    }

    public Optional<GameAttribute> findById(int id) {
        return dsl.selectFrom(GAME_ATTRIBUTE)
                .where(GAME_ATTRIBUTE.ID.eq(id))
                .fetchOptional()
                .map(this::toAttribute);
    }

    /** @param exceptId the row being edited, so keeping your own key is not a clash. */
    public boolean keyTaken(short gameId, String attrKey, Integer exceptId) {
        return dsl.fetchExists(dsl.selectOne().from(GAME_ATTRIBUTE)
                .where(GAME_ATTRIBUTE.GAME_ID.eq(gameId))
                .and(GAME_ATTRIBUTE.ATTR_KEY.eq(attrKey))
                .and(exceptId == null ? DSL.noCondition() : GAME_ATTRIBUTE.ID.ne(exceptId)));
    }

    public int insert(short gameId, AttributeFields fields) {
        return dsl.insertInto(GAME_ATTRIBUTE)
                .set(GAME_ATTRIBUTE.GAME_ID, gameId)
                .set(GAME_ATTRIBUTE.ATTR_KEY, fields.attrKey())
                .set(GAME_ATTRIBUTE.LABEL, fields.label())
                .set(GAME_ATTRIBUTE.DATA_TYPE, fields.dataType().name())
                .set(GAME_ATTRIBUTE.OPTIONS, toJsonb(fields))
                .set(GAME_ATTRIBUTE.IS_FILTERABLE, fields.filterable())
                .set(GAME_ATTRIBUTE.IS_REQUIRED, fields.required())
                .set(GAME_ATTRIBUTE.DISPLAY_ORDER, fields.displayOrder())
                .returningResult(GAME_ATTRIBUTE.ID)
                .fetchSingle(GAME_ATTRIBUTE.ID);
    }

    public boolean update(int id, AttributeFields fields) {
        return dsl.update(GAME_ATTRIBUTE)
                .set(GAME_ATTRIBUTE.ATTR_KEY, fields.attrKey())
                .set(GAME_ATTRIBUTE.LABEL, fields.label())
                .set(GAME_ATTRIBUTE.DATA_TYPE, fields.dataType().name())
                .set(GAME_ATTRIBUTE.OPTIONS, toJsonb(fields))
                .set(GAME_ATTRIBUTE.IS_FILTERABLE, fields.filterable())
                .set(GAME_ATTRIBUTE.IS_REQUIRED, fields.required())
                .set(GAME_ATTRIBUTE.DISPLAY_ORDER, fields.displayOrder())
                .where(GAME_ATTRIBUTE.ID.eq(id))
                .execute() > 0;
    }

    /** No table references an attribute row, so this one really is a delete. */
    public boolean delete(int id) {
        return dsl.deleteFrom(GAME_ATTRIBUTE).where(GAME_ATTRIBUTE.ID.eq(id)).execute() > 0;
    }

    /** Only an ENUM carries a choice list, which is what the column CHECK also says. */
    private JSONB toJsonb(AttributeFields fields) {
        if (!fields.dataType().requiresOptions()) {
            return null;
        }
        return JSONB.valueOf(json.writeValueAsString(fields.options()));
    }

    private GameAttribute toAttribute(GameAttributeRecord r) {
        List<String> options = r.getOptions() == null
                ? List.of()
                : json.readValue(r.getOptions().data(), STRING_LIST);

        return new GameAttribute(
                r.getId(),
                r.getGameId(),
                r.getAttrKey(),
                r.getLabel(),
                AttributeDataType.valueOf(r.getDataType()),
                options,
                r.getIsFilterable(),
                r.getIsRequired(),
                r.getDisplayOrder());
    }

    public record AttributeFields(
            String attrKey,
            String label,
            AttributeDataType dataType,
            List<String> options,
            boolean filterable,
            boolean required,
            short displayOrder) {
    }
}
