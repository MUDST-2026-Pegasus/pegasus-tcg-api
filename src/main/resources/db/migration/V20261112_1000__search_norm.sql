-- TCG-772: one normaliser for the query and the searched text, so the same rules apply on both sides.
-- Java mirror: repository.catalog.SearchText.normalize — keep the two in step.
CREATE EXTENSION IF NOT EXISTS unaccent;

-- lower-case, no Latin accents (Pokémon = pokemon), no Thai tone marks (โปเกม่อน = โปเกมอน),
-- card numbers folded (#020, 020-189, 20/189 all read 20/189), single spaces.
CREATE OR REPLACE FUNCTION f_search_norm(text) RETURNS text
    LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE AS $$
    SELECT btrim(regexp_replace(
        regexp_replace(
            replace(replace(
                lower(public.unaccent('public.unaccent'::regdictionary,
                    regexp_replace($1, '[่้๊๋็์]', '', 'g'))),
                '#', ''), '-', '/'),
            '(^|[^0-9])0+([0-9])', '\1\2', 'g'),
        '\s+', ' ', 'g'))
$$;

CREATE INDEX ix_catalog_product_name_norm_trgm
    ON catalog_product USING GIN (f_search_norm(name) gin_trgm_ops);
CREATE INDEX ix_catalog_product_name_local_norm_trgm
    ON catalog_product USING GIN (f_search_norm(name_local) gin_trgm_ops);
