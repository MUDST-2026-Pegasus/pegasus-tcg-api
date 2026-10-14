package com.pegasus.pegasustcgapi.repository;

import com.pegasus.pegasustcgapi.dto.CatalogCardEffectResponse;
import java.util.List;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Repository
public class CatalogCardEffectRepository {

    private static final TypeReference<List<String>> ENERGY_COST_TYPE = new TypeReference<>() {};
    // Only English rules are entered so far; other languages get their own rows later.
    private static final String ENGLISH = "EN";
    // Use typed jOOQ fields so this reader also compiles before local codegen has
    // been re-run against the new migration.
    private static final Table<?> EFFECT = DSL.table(DSL.name("catalog_card_effect"));
    private static final Field<Long> PRODUCT_ID = DSL.field(DSL.name("catalog_product_id"), Long.class);
    private static final Field<String> LANGUAGE = DSL.field(DSL.name("language_code"), String.class);
    private static final Field<String> TYPE = DSL.field(DSL.name("effect_type"), String.class);
    private static final Field<String> NAME = DSL.field(DSL.name("name"), String.class);
    private static final Field<String> TEXT = DSL.field(DSL.name("effect_text"), String.class);
    private static final Field<Integer> DAMAGE = DSL.field(DSL.name("damage"), Integer.class);
    private static final Field<JSONB> ENERGY_COST = DSL.field(DSL.name("energy_cost"), JSONB.class);
    private static final Field<Short> DISPLAY_ORDER = DSL.field(DSL.name("display_order"), Short.class);
    private static final Field<String> SOURCE_URL = DSL.field(DSL.name("source_url"), String.class);

    /** What an admin enters for one effect; its display order is its place in the list. */
    public record EffectFields(
            String effectType,
            String name,
            String effectText,
            Integer damage,
            List<String> energyCost,
            String sourceUrl) {
    }

    private final DSLContext dsl;
    private final ObjectMapper json;

    public CatalogCardEffectRepository(DSLContext dsl, ObjectMapper json) {
        this.dsl = dsl;
        this.json = json;
    }

    public List<CatalogCardEffectResponse> findByProductId(long productId) {
        return dsl.select(TYPE, NAME, TEXT, DAMAGE, ENERGY_COST, DISPLAY_ORDER, SOURCE_URL)
                .from(EFFECT)
                .where(PRODUCT_ID.eq(productId))
                .and(LANGUAGE.eq(ENGLISH))
                .orderBy(DISPLAY_ORDER.asc())
                .fetch(row -> new CatalogCardEffectResponse(
                        row.get(TYPE),
                        row.get(NAME),
                        row.get(TEXT),
                        row.get(DAMAGE),
                        parseEnergyCost(row.get(ENERGY_COST).data()),
                        row.get(DISPLAY_ORDER),
                        row.get(SOURCE_URL)));
    }

    /**
     * Swaps the product's English effects for these, numbered in list order.
     * Deleting first keeps a reorder from tripping the unique display order
     * mid-update; the caller's transaction makes the swap all-or-nothing.
     */
    public void replaceForProduct(long productId, List<EffectFields> effects) {
        dsl.deleteFrom(EFFECT)
                .where(PRODUCT_ID.eq(productId))
                .and(LANGUAGE.eq(ENGLISH))
                .execute();
        if (effects.isEmpty()) {
            return;
        }

        var insert = dsl.insertInto(EFFECT,
                PRODUCT_ID, LANGUAGE, TYPE, NAME, TEXT, DAMAGE, ENERGY_COST, DISPLAY_ORDER, SOURCE_URL);
        for (int i = 0; i < effects.size(); i++) {
            EffectFields effect = effects.get(i);
            insert = insert.values(productId, ENGLISH, effect.effectType(), effect.name(),
                    effect.effectText(), effect.damage(),
                    JSONB.valueOf(json.writeValueAsString(effect.energyCost())),
                    (short) i, effect.sourceUrl());
        }
        insert.execute();
    }

    private List<String> parseEnergyCost(String value) {
        return json.readValue(value, ENERGY_COST_TYPE);
    }
}
