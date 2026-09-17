-- Listing stock under concurrency, and the seller gate the schema guide asks for.
--
-- 1. refresh_listing_quantities counted a listing's cards inside the same UPDATE
--    that wrote the counts. Two checkouts holding two different cards of one
--    listing each counted before the other committed; the second then waited for
--    the listing's row lock and, once it had it, wrote its stale count over the
--    first. Reproduced against the previous function with two psql sessions:
--    cached quantity_reserved=1 quantity_available=1, while the cards said
--    RESERVED=2 LISTED=0. The function now takes the row lock first and counts
--    afterwards. Under READ COMMITTED each statement in a plpgsql function takes
--    a fresh snapshot, so the count sees every transaction that held the lock
--    before this one.
--
-- 2. The recount also moves ACTIVE to SOLD_OUT as the last card goes, and back as
--    a card returns, so no caller has to remember to.
--
-- 3. A card moving between two listings recounts both lower id first, so two
--    moves in opposite directions take the pair of row locks in the same order.
--    An update that changes neither status, listing nor deletion — a note, a
--    cert number — no longer recounts at all.
--
-- 4. Only a VERIFIED seller may create a listing or put one on sale [RQ-1]. The
--    service checks first and answers 403; this is the backstop for anything
--    else that writes the table.

CREATE OR REPLACE FUNCTION refresh_listing_quantities(p_listing_id bigint) RETURNS void
    LANGUAGE plpgsql AS $$
DECLARE
    listed   int;
    reserved int;
BEGIN
    -- Lock, then count. Counting inside the UPDATE is the bug this replaces.
    PERFORM 1 FROM listing WHERE id = p_listing_id FOR NO KEY UPDATE;

    SELECT count(*) FILTER (WHERE status = 'LISTED'),
           count(*) FILTER (WHERE status = 'RESERVED')
      INTO listed, reserved
      FROM listing_unit
     WHERE listing_id = p_listing_id
       AND deleted_at IS NULL;

    UPDATE listing
       SET quantity_available = listed,
           quantity_reserved  = reserved,
           quantity_total     = listed + reserved,
           status = CASE
                        WHEN status = 'ACTIVE'   AND listed = 0 THEN 'SOLD_OUT'
                        WHEN status = 'SOLD_OUT' AND listed > 0 THEN 'ACTIVE'
                        ELSE status
                    END,
           version = version + 1
     WHERE id = p_listing_id;
END;
$$;


CREATE OR REPLACE FUNCTION listing_unit_refresh_quantities() RETURNS trigger
    LANGUAGE plpgsql AS $$
DECLARE
    old_listing bigint;
    new_listing bigint;
BEGIN
    IF TG_OP = 'UPDATE'
       AND OLD.listing_id IS NOT DISTINCT FROM NEW.listing_id
       AND OLD.status = NEW.status
       AND OLD.deleted_at IS NOT DISTINCT FROM NEW.deleted_at THEN
        RETURN NULL;
    END IF;

    IF TG_OP IN ('UPDATE', 'DELETE') THEN
        old_listing := OLD.listing_id;
    END IF;
    IF TG_OP IN ('INSERT', 'UPDATE') THEN
        new_listing := NEW.listing_id;
    END IF;

    IF old_listing IS NOT NULL AND new_listing IS NOT NULL AND old_listing <> new_listing THEN
        PERFORM refresh_listing_quantities(LEAST(old_listing, new_listing));
        PERFORM refresh_listing_quantities(GREATEST(old_listing, new_listing));
    ELSIF coalesce(old_listing, new_listing) IS NOT NULL THEN
        PERFORM refresh_listing_quantities(coalesce(old_listing, new_listing));
    END IF;

    RETURN NULL;
END;
$$;


CREATE FUNCTION listing_requires_verified_seller() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    -- Checked on INSERT, and on a move to ACTIVE from anything but SOLD_OUT.
    -- SOLD_OUT -> ACTIVE is the recount bringing a listing back when a released or
    -- returned card lands on it; refusing that would fail the checkout or the
    -- return that released the card, not the seller.
    IF TG_OP = 'UPDATE'
       AND NOT (NEW.status = 'ACTIVE' AND OLD.status NOT IN ('ACTIVE', 'SOLD_OUT')) THEN
        RETURN NEW;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM seller_profile
                    WHERE id = NEW.seller_profile_id
                      AND status = 'VERIFIED') THEN
        RAISE EXCEPTION 'seller_profile % is not VERIFIED and cannot create or publish a listing',
            NEW.seller_profile_id
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_listing_verified_seller
    BEFORE INSERT OR UPDATE OF status ON listing
    FOR EACH ROW EXECUTE FUNCTION listing_requires_verified_seller();
