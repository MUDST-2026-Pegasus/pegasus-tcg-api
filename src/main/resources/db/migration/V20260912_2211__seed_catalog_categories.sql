-- The shelves every game needs, so a game added from the back office has
-- somewhere to file its cards the moment it exists.
--
-- game_id stays NULL, which the schema reads as "applies to every game". A game
-- that needs its own shelf adds one; nobody has to recreate these four per game.
--
-- WHERE NOT EXISTS rather than ON CONFLICT: ux_catalog_category_code is a
-- partial-free but NULLS NOT DISTINCT index, and an explicit existence check
-- says what is meant without depending on how the index infers a conflict.
INSERT INTO catalog_category (game_id, code, name, slug, display_order)
SELECT v.game_id, v.code, v.name, v.slug, v.display_order
  FROM (VALUES
        (NULL::smallint, 'SINGLES',   'Single cards',   'single-cards',   1::smallint),
        (NULL::smallint, 'SEALED',    'Sealed product', 'sealed-product', 2::smallint),
        (NULL::smallint, 'ACCESSORY', 'Accessories',    'accessories',    3::smallint),
        (NULL::smallint, 'OTHER',     'Other',          'other',          9::smallint)
       ) AS v(game_id, code, name, slug, display_order)
 WHERE NOT EXISTS (
        SELECT 1 FROM catalog_category c
         WHERE c.code = v.code
           AND c.game_id IS NOT DISTINCT FROM v.game_id);
