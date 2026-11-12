-- TCG-773: words people type (often Thai) that should search as another word.
CREATE TABLE search_alias (
    id          bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    alias       varchar(60)  NOT NULL,
    alias_norm  text         GENERATED ALWAYS AS (f_search_norm(alias)) STORED,
    target      varchar(60)  NOT NULL,
    -- NULL: the alias applies to every game.
    game_id     smallint     REFERENCES game (id) ON DELETE CASCADE,
    created_by  bigint       REFERENCES user_account (id) ON DELETE SET NULL,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ux_search_alias UNIQUE NULLS NOT DISTINCT (alias_norm, target, game_id)
);

CREATE INDEX ix_search_alias_norm_trgm ON search_alias USING GIN (alias_norm gin_trgm_ops);
