-- A slide's buttons lead somewhere on this site, never off it: the home page puts
-- them in front of every visitor, so an outside URL here is an open redirect.
-- A path has to start with one slash; "//host" and "/\host" are refused too,
-- because a browser reads both as a link to another host.
ALTER TABLE home_banner
    ADD CONSTRAINT ck_home_banner_primary_href
        CHECK (primary_href IS NULL OR primary_href ~ '^/($|[^/\\])'),
    ADD CONSTRAINT ck_home_banner_secondary_href
        CHECK (secondary_href IS NULL OR secondary_href ~ '^/($|[^/\\])');
