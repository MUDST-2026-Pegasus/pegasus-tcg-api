-- Search over the local-language name.
--
-- The catalogue migration gave `name` a trigram index, which is what makes
-- "pikachu" match "Pikachu ex" without scanning the table. Thai buyers type the
-- Thai name, so name_local needs the same treatment or every such search falls
-- back to a sequential scan once the catalogue is real.
CREATE INDEX ix_catalog_product_name_local_trgm
    ON catalog_product USING GIN (name_local gin_trgm_ops);
