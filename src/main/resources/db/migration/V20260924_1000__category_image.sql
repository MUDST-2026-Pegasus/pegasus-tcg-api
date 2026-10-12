-- The home page shows categories as picture tiles, so a shelf needs a picture.
-- Stored as an object key, like catalog_image, and signed when it is read.
ALTER TABLE catalog_category
    ADD COLUMN image_key varchar(500);

COMMENT ON COLUMN catalog_category.image_key IS
    'MinIO object key for the category tile, not a public URL. NULL shows no picture.';
