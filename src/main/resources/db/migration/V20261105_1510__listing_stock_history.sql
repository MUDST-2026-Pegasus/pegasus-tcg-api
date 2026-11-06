-- Keep enough listing state to answer weekly stock questions as of a past cutoff.
CREATE TABLE listing_stock_history (
    id                bigint      PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    listing_id        bigint      NOT NULL REFERENCES listing (id) ON DELETE CASCADE,
    status            varchar(32) NOT NULL,
    quantity_available int        NOT NULL,
    deleted_at        timestamptz,
    changed_at        timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX ix_listing_stock_history_as_of
    ON listing_stock_history (listing_id, changed_at DESC, id DESC);

-- The first row is the best known baseline; earlier states cannot be recovered
-- because the old listing table did not retain temporal status or stock.
INSERT INTO listing_stock_history (listing_id, status, quantity_available, deleted_at, changed_at)
SELECT id, status, quantity_available, deleted_at, now()
  FROM listing;

CREATE FUNCTION record_listing_stock_history() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO listing_stock_history (listing_id, status, quantity_available, deleted_at, changed_at)
    VALUES (NEW.id, NEW.status, NEW.quantity_available, NEW.deleted_at, now());
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_listing_stock_history
    AFTER INSERT OR UPDATE OF status, quantity_available, deleted_at ON listing
    FOR EACH ROW EXECUTE FUNCTION record_listing_stock_history();
