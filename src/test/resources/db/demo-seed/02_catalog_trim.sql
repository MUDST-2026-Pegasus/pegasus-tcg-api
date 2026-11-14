-- Demo seed: the catalogue. Loaded right after full-seed/03_catalog.sql, which
-- brings seven games; the demo keeps Pokemon and One Piece only (82 products:
-- singles, sealed boxes and packs, accessories) and removes the rest.
--
-- Nothing points at these rows yet (no listings, no orders), so a plain delete is
-- enough. Variants, pictures and card rules go with their product; card sets and
-- attributes go with their game.

DELETE FROM catalog_product
 WHERE game_id IN (SELECT id FROM game WHERE code NOT IN ('POKEMON', 'ONE_PIECE'));

DELETE FROM catalog_category
 WHERE game_id IN (SELECT id FROM game WHERE code NOT IN ('POKEMON', 'ONE_PIECE'));

DELETE FROM game WHERE code NOT IN ('POKEMON', 'ONE_PIECE');

-- Shelves whose picture came from a removed game's product get one from a product
-- that is still here.
UPDATE catalog_category SET image_key = 'seed/products/ultra-pro-lillie-clefairy-sleeves-65.png'
 WHERE code = 'ACCESSORY' AND game_id IS NULL;
UPDATE catalog_category SET image_key = 'seed/products/dragon-shield-strongbox-clear.png'
 WHERE code = 'DECK_BOXES' AND game_id IS NULL;

-- Any other shelf still pointing at a picture no product uses loses it rather than
-- showing a broken image.
UPDATE catalog_category c SET image_key = NULL
 WHERE c.image_key IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM catalog_image ci WHERE ci.image_key = c.image_key);
