-- Card rules are structured data, separate from faceted product attributes.
-- One product can have abilities, attacks, and other rules in display order.
CREATE TABLE IF NOT EXISTS catalog_card_effect (
    id                 bigint       PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    catalog_product_id bigint       NOT NULL REFERENCES catalog_product (id) ON DELETE CASCADE,
    language_code      varchar(10)  NOT NULL DEFAULT 'EN',
    effect_type        varchar(16)  NOT NULL,
    name               varchar(120) NOT NULL,
    effect_text        text,
    damage             int,
    energy_cost        jsonb        NOT NULL DEFAULT '[]'::jsonb,
    display_order      smallint     NOT NULL DEFAULT 0,
    source_url         varchar(500),
    created_at         timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT ck_catalog_card_effect_type CHECK (effect_type IN ('ABILITY', 'ATTACK', 'RULE')),
    CONSTRAINT ck_catalog_card_effect_name CHECK (btrim(name) <> ''),
    CONSTRAINT ck_catalog_card_effect_damage CHECK (damage IS NULL OR damage >= 0),
    CONSTRAINT ck_catalog_card_effect_energy CHECK (jsonb_typeof(energy_cost) = 'array'),
    CONSTRAINT uq_catalog_card_effect_order UNIQUE (catalog_product_id, language_code, display_order)
);

CREATE INDEX IF NOT EXISTS ix_catalog_card_effect_product
    ON catalog_card_effect (catalog_product_id, language_code, display_order);
