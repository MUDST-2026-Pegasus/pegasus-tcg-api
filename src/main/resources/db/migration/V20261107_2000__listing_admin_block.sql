-- An admin suspends a listing for a stated reason [TCG-730]. BLOCKED has been a
-- legal status since the table was made, but nothing could set it, so nothing
-- recorded why. The status a listing was blocked from is what lifting restores.
ALTER TABLE listing
    ADD COLUMN blocked_reason      text,
    ADD COLUMN blocked_by          bigint REFERENCES user_account (id),
    ADD COLUMN blocked_at          timestamptz,
    ADD COLUMN blocked_from_status varchar(16),
    ADD CONSTRAINT ck_listing_blocked_from_status CHECK (blocked_from_status IS NULL
        OR blocked_from_status IN ('DRAFT', 'ACTIVE', 'PAUSED', 'SOLD_OUT'));

-- The admin catalogue filters and counts blocked listings per card.
CREATE INDEX ix_listing_blocked_variant ON listing (catalog_variant_id)
    WHERE status = 'BLOCKED' AND deleted_at IS NULL;
