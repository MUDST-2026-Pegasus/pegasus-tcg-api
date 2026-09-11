-- Schema sprint 3/7 — what a seller actually offers, and the stock behind it.
--
-- The split that carries the whole design: `listing` is a PRICE GROUP, not a
-- card. `listing_unit` is one physical card with its own UUID. Six identical
-- cards can sit under two listings at two prices; editing one price moves every
-- card in that group at once, and a card can move between groups without losing
-- the identity that ties it to its sale history.

CREATE TABLE listing (
    id                        bigint        PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    seller_profile_id         bigint        NOT NULL REFERENCES seller_profile (id) ON DELETE CASCADE,
    catalog_variant_id        bigint        NOT NULL REFERENCES catalog_variant (id) ON DELETE RESTRICT,
    condition_code            varchar(16)   NOT NULL DEFAULT 'NM',
    grading_company           varchar(20),
    grade_value               decimal(3,1),

    price                     decimal(14,2) NOT NULL,
    currency                  char(3)       NOT NULL DEFAULT 'THB',
    pricing_mode              varchar(32)   NOT NULL DEFAULT 'MANUAL',
    auto_price_offset_percent decimal(6,2),
    auto_price_floor          decimal(14,2),
    auto_price_ceiling        decimal(14,2),
    last_auto_priced_at       timestamptz,

    quantity_total            int           NOT NULL DEFAULT 0,
    quantity_reserved         int           NOT NULL DEFAULT 0,
    quantity_available        int           NOT NULL DEFAULT 0,

    status                    varchar(32)   NOT NULL DEFAULT 'DRAFT',
    lot_label                 varchar(60),
    public_note               varchar(500),
    version                   int           NOT NULL DEFAULT 0,
    published_at              timestamptz,
    created_at                timestamptz   NOT NULL DEFAULT now(),
    updated_at                timestamptz   NOT NULL DEFAULT now(),
    deleted_at                timestamptz,

    CONSTRAINT ck_listing_condition CHECK (condition_code IN
        ('NM', 'LP', 'MP', 'HP', 'DMG', 'SEALED')),
    CONSTRAINT ck_listing_status CHECK (status IN
        ('DRAFT', 'ACTIVE', 'PAUSED', 'SOLD_OUT', 'DELISTED', 'BLOCKED')),
    CONSTRAINT ck_listing_pricing_mode CHECK (pricing_mode IN ('MANUAL', 'AUTO_MEDIAN')),
    CONSTRAINT ck_listing_price CHECK (price > 0),
    CONSTRAINT ck_listing_quantities CHECK (
        quantity_total >= 0 AND quantity_reserved >= 0 AND quantity_available >= 0
        AND quantity_available = quantity_total - quantity_reserved),
    CONSTRAINT ck_listing_auto_price_band CHECK (
        auto_price_floor IS NULL OR auto_price_ceiling IS NULL
        OR auto_price_floor <= auto_price_ceiling),
    CONSTRAINT ck_listing_grade CHECK (
        (grading_company IS NULL AND grade_value IS NULL) OR grading_company IS NOT NULL)
);

COMMENT ON TABLE listing IS
    'One asking price covering any number of identical cards [RQ-9].';
COMMENT ON COLUMN listing.quantity_available IS
    'Cache of listing_unit; the trigger below is the only thing allowed to set it.';
COMMENT ON COLUMN listing.lot_label IS
    'The seller''s own name for this group, so two listings of one card stay apart on the dashboard.';

-- Makes "who sells this card, cheapest first" fast even at millions of rows.
CREATE INDEX ix_listing_market
    ON listing (catalog_variant_id, condition_code, status, price);
CREATE INDEX ix_listing_seller_dashboard
    ON listing (seller_profile_id, status, updated_at);
CREATE INDEX ix_listing_auto_price_job
    ON listing (status, pricing_mode);

-- DELIBERATELY ABSENT: ux_listing_active (seller_profile_id, catalog_variant_id,
-- condition_code). One seller holding the same card at two prices is the point
-- of this design, not a mistake to prevent. Do not add a version with `price`
-- appended either: two AUTO_MEDIAN listings converge on the same price by
-- themselves and the nightly repricing job would then fail for the whole batch.
-- The duplicate is surfaced as a service-layer prompt ("merge into the existing
-- listing?"), never as a constraint.

CREATE TRIGGER trg_listing_updated_at
    BEFORE UPDATE ON listing
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();


-- One physical card, one row, one UUID for its whole life.
CREATE TABLE listing_unit (
    id                 bigint        PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    public_uid         uuid          NOT NULL DEFAULT gen_random_uuid(),
    seller_profile_id  bigint        NOT NULL REFERENCES seller_profile (id) ON DELETE CASCADE,
    catalog_variant_id bigint        NOT NULL REFERENCES catalog_variant (id) ON DELETE RESTRICT,
    condition_code     varchar(16)   NOT NULL,
    listing_id         bigint        REFERENCES listing (id) ON DELETE SET NULL,
    status             varchar(32)   NOT NULL DEFAULT 'IN_STOCK',
    cert_number        varchar(64),
    acquisition_cost   decimal(14,2),
    acquired_at        timestamptz,
    unit_note          varchar(255),
    sold_at            timestamptz,
    created_at         timestamptz   NOT NULL DEFAULT now(),
    updated_at         timestamptz   NOT NULL DEFAULT now(),
    deleted_at         timestamptz,

    CONSTRAINT uq_listing_unit_public_uid UNIQUE (public_uid),
    CONSTRAINT ck_listing_unit_condition CHECK (condition_code IN
        ('NM', 'LP', 'MP', 'HP', 'DMG', 'SEALED')),
    CONSTRAINT ck_listing_unit_status CHECK (status IN
        ('IN_STOCK', 'LISTED', 'RESERVED', 'SOLD', 'RETURNED', 'WRITTEN_OFF')),
    -- In hand but not on sale means it belongs to no listing.
    CONSTRAINT ck_listing_unit_in_stock_unlisted CHECK (
        status <> 'IN_STOCK' OR listing_id IS NULL),
    -- On sale or reserved means it must belong to one.
    CONSTRAINT ck_listing_unit_listed_has_listing CHECK (
        status NOT IN ('LISTED', 'RESERVED') OR listing_id IS NOT NULL)
);

COMMENT ON COLUMN listing_unit.public_uid IS
    'The id shown on the site and on a QR code. Never expose the sequential id.';
COMMENT ON COLUMN listing_unit.seller_profile_id IS
    'The real owner of this card; unchanged when the card moves to another listing.';
COMMENT ON COLUMN listing_unit.acquired_at IS
    'Used to pick the oldest card first (FIFO) when filling an order.';

CREATE INDEX ix_listing_unit_listing ON listing_unit (listing_id, status);
CREATE INDEX ix_listing_unit_seller_stock
    ON listing_unit (seller_profile_id, catalog_variant_id, condition_code, status);
-- Drives the reservation query: WHERE status = 'LISTED' ORDER BY acquired_at
-- LIMIT :qty FOR UPDATE SKIP LOCKED.
CREATE INDEX ix_listing_unit_fifo
    ON listing_unit (seller_profile_id, status, acquired_at);
CREATE INDEX ix_listing_unit_cert ON listing_unit (cert_number);

CREATE TRIGGER trg_listing_unit_updated_at
    BEFORE UPDATE ON listing_unit
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();


-- A card may only join a listing that is the same card, same condition, from the
-- same seller. Without this, a Japanese print can silently end up priced inside
-- an English listing.
CREATE FUNCTION listing_unit_must_match_listing() RETURNS trigger
    LANGUAGE plpgsql AS $$
DECLARE
    target listing%ROWTYPE;
BEGIN
    IF NEW.listing_id IS NULL THEN
        RETURN NEW;
    END IF;

    SELECT * INTO target FROM listing WHERE id = NEW.listing_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'listing % does not exist', NEW.listing_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;

    IF target.seller_profile_id  <> NEW.seller_profile_id
       OR target.catalog_variant_id <> NEW.catalog_variant_id
       OR target.condition_code     <> NEW.condition_code THEN
        RAISE EXCEPTION
            'listing_unit cannot join listing %: seller/variant/condition do not match',
            NEW.listing_id
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_listing_unit_match_listing
    BEFORE INSERT OR UPDATE OF listing_id, seller_profile_id, catalog_variant_id, condition_code
    ON listing_unit
    FOR EACH ROW EXECUTE FUNCTION listing_unit_must_match_listing();


-- listing.quantity_* is derived, never written by hand. Regression test #2 reads
-- exactly these two counts back out.
CREATE FUNCTION refresh_listing_quantities(p_listing_id bigint) RETURNS void
    LANGUAGE plpgsql AS $$
BEGIN
    UPDATE listing l
       SET quantity_available = counts.listed,
           quantity_reserved  = counts.reserved,
           quantity_total     = counts.listed + counts.reserved
      FROM (
            SELECT count(*) FILTER (WHERE status = 'LISTED')::int   AS listed,
                   count(*) FILTER (WHERE status = 'RESERVED')::int AS reserved
              FROM listing_unit
             WHERE listing_id = p_listing_id
               AND deleted_at IS NULL
           ) AS counts
     WHERE l.id = p_listing_id;
END;
$$;

CREATE FUNCTION listing_unit_refresh_quantities() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    -- The listing the card is leaving (or the one it stayed in after a status change).
    IF TG_OP IN ('UPDATE', 'DELETE') AND OLD.listing_id IS NOT NULL THEN
        PERFORM refresh_listing_quantities(OLD.listing_id);
    END IF;

    -- The listing it is joining.
    IF TG_OP IN ('INSERT', 'UPDATE') AND NEW.listing_id IS NOT NULL
       AND (TG_OP = 'INSERT' OR OLD.listing_id IS DISTINCT FROM NEW.listing_id) THEN
        PERFORM refresh_listing_quantities(NEW.listing_id);
    END IF;

    RETURN NULL;
END;
$$;

CREATE TRIGGER trg_listing_unit_quantities
    AFTER INSERT OR UPDATE OR DELETE ON listing_unit
    FOR EACH ROW EXECUTE FUNCTION listing_unit_refresh_quantities();


CREATE TABLE listing_image (
    id         bigint       PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    listing_id bigint       NOT NULL REFERENCES listing (id) ON DELETE CASCADE,
    image_key  varchar(500) NOT NULL,
    sort_order smallint     NOT NULL DEFAULT 0,
    is_primary boolean      NOT NULL DEFAULT false,
    created_at timestamptz  NOT NULL DEFAULT now()
);

COMMENT ON TABLE listing_image IS
    'Photos of the actual card, taken by the seller [RQ-8]. Official art lives in catalog_image.';

CREATE INDEX ix_listing_image_listing ON listing_image (listing_id, sort_order);
CREATE UNIQUE INDEX ux_listing_image_primary
    ON listing_image (listing_id)
    WHERE is_primary;


CREATE TABLE listing_price_history (
    id           bigint        PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    listing_id   bigint        NOT NULL REFERENCES listing (id) ON DELETE CASCADE,
    old_price    decimal(14,2),
    new_price    decimal(14,2) NOT NULL,
    pricing_mode varchar(32)   NOT NULL,
    changed_by   bigint        REFERENCES user_account (id) ON DELETE SET NULL,
    reason       varchar(100),
    created_at   timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT ck_price_history_mode CHECK (pricing_mode IN ('MANUAL', 'AUTO_MEDIAN'))
);

COMMENT ON COLUMN listing_price_history.changed_by IS
    'NULL means the nightly repricing job did it, not a person [RQ-5,6].';

CREATE INDEX ix_listing_price_history_listing ON listing_price_history (listing_id, created_at);


-- Where median price comes from. Computed nightly with
-- PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY price) over ACTIVE listings.
CREATE TABLE variant_market_stat (
    id                   bigint        PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    catalog_variant_id   bigint        NOT NULL REFERENCES catalog_variant (id) ON DELETE CASCADE,
    condition_code       varchar(16)   NOT NULL,
    stat_date            date          NOT NULL,
    median_price         decimal(14,2),
    avg_price            decimal(14,2),
    min_price            decimal(14,2),
    max_price            decimal(14,2),
    p25_price            decimal(14,2),
    p75_price            decimal(14,2),
    active_listing_count int           NOT NULL DEFAULT 0,
    sold_quantity_30d    int           NOT NULL DEFAULT 0,
    sample_size          int           NOT NULL DEFAULT 0,
    computed_at          timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT ck_market_stat_condition CHECK (condition_code IN
        ('NM', 'LP', 'MP', 'HP', 'DMG', 'SEALED'))
);

COMMENT ON COLUMN variant_market_stat.sample_size IS
    'Below about 3 the median is noise; the repricing job should skip those rows.';
COMMENT ON TABLE variant_market_stat IS
    'Grows by the day. When volume warrants it, convert to PARTITION BY RANGE (stat_date), monthly.';

CREATE UNIQUE INDEX ux_market_stat_key
    ON variant_market_stat (catalog_variant_id, condition_code, stat_date);
CREATE INDEX ix_market_stat_date ON variant_market_stat (stat_date);


-- Append-only stock ledger. listing.quantity_* must always reconcile against it.
CREATE TABLE inventory_movement (
    id                 bigint        PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    seller_profile_id  bigint        NOT NULL REFERENCES seller_profile (id) ON DELETE CASCADE,
    listing_id         bigint        REFERENCES listing (id) ON DELETE SET NULL,
    listing_unit_id    bigint        REFERENCES listing_unit (id) ON DELETE SET NULL,
    catalog_variant_id bigint        NOT NULL REFERENCES catalog_variant (id) ON DELETE RESTRICT,
    condition_code     varchar(16)   NOT NULL,
    movement_type      varchar(32)   NOT NULL,
    quantity_delta     int           NOT NULL,
    unit_cost          decimal(14,2),
    reference_type     varchar(32),
    reference_id       bigint,
    note               varchar(255),
    created_by         bigint        REFERENCES user_account (id) ON DELETE SET NULL,
    created_at         timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT ck_inventory_movement_condition CHECK (condition_code IN
        ('NM', 'LP', 'MP', 'HP', 'DMG', 'SEALED')),
    CONSTRAINT ck_inventory_movement_type CHECK (movement_type IN
        ('INITIAL_STOCK', 'RESTOCK', 'SALE', 'CANCEL_RESTOCK',
         'RETURN_RESTOCK', 'ADJUSTMENT', 'LOSS')),
    CONSTRAINT ck_inventory_movement_delta CHECK (quantity_delta <> 0),
    -- Cost is what makes profit computable, so inbound stock has to carry it.
    CONSTRAINT ck_inventory_movement_cost CHECK (
        quantity_delta < 0 OR movement_type = 'ADJUSTMENT' OR unit_cost IS NOT NULL),
    CONSTRAINT ck_inventory_movement_reference CHECK (
        (reference_type IS NULL AND reference_id IS NULL)
        OR (reference_type IS NOT NULL AND reference_id IS NOT NULL))
);

COMMENT ON COLUMN inventory_movement.listing_unit_id IS
    'Set for per-card movements, so one UUID''s whole in-and-out history is readable.';
COMMENT ON COLUMN inventory_movement.quantity_delta IS
    'Positive is inbound, negative is outbound.';

CREATE INDEX ix_inventory_movement_seller ON inventory_movement (seller_profile_id, created_at);
CREATE INDEX ix_inventory_movement_variant
    ON inventory_movement (catalog_variant_id, condition_code);
CREATE INDEX ix_inventory_movement_ref ON inventory_movement (reference_type, reference_id);
CREATE INDEX ix_inventory_movement_unit ON inventory_movement (listing_unit_id);


-- Moving weighted average cost per seller [RQ-7]:
--   inbound:  total_quantity += q;  total_cost += q * unit_cost
--   outbound: total_cost -= q * average_unit_cost;  total_quantity -= q
-- Profit per order = line_total - (unit_cost_snapshot * qty) - commission share.
CREATE TABLE seller_variant_cost (
    id                 bigint        PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    seller_profile_id  bigint        NOT NULL REFERENCES seller_profile (id) ON DELETE CASCADE,
    catalog_variant_id bigint        NOT NULL REFERENCES catalog_variant (id) ON DELETE RESTRICT,
    condition_code     varchar(16)   NOT NULL,
    total_quantity     int           NOT NULL DEFAULT 0,
    total_cost         decimal(16,2) NOT NULL DEFAULT 0,
    average_unit_cost  decimal(14,4) NOT NULL DEFAULT 0,
    last_movement_at   timestamptz,
    updated_at         timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT ck_seller_variant_cost_condition CHECK (condition_code IN
        ('NM', 'LP', 'MP', 'HP', 'DMG', 'SEALED')),
    CONSTRAINT ck_seller_variant_cost_non_negative CHECK (
        total_quantity >= 0 AND total_cost >= 0 AND average_unit_cost >= 0)
);

CREATE UNIQUE INDEX ux_seller_variant_cost
    ON seller_variant_cost (seller_profile_id, catalog_variant_id, condition_code);

CREATE TRIGGER trg_seller_variant_cost_updated_at
    BEFORE UPDATE ON seller_variant_cost
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
