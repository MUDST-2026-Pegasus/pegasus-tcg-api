-- Direct price discounts [TCG-676, epic TCG-675]. Unlike a discount code, the
-- buyer types nothing: the listing simply shows a lower price while a sale runs.
-- Pegasus runs PLATFORM campaigns (flash sales, a game/category/set on sale) and
-- pays the difference; a shop runs SELLER sales on its own listings and pays for
-- them itself. A listing takes one sale price at a time, the lowest one.
-- Status (scheduled, active, paused, ended, sold out, budget used) is worked out
-- from these columns, never stored.
CREATE TABLE price_sale (
    id                    bigint        PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    name                  varchar(120)  NOT NULL,
    slug                  varchar(80)   NOT NULL,
    owner_type            varchar(16)   NOT NULL,
    seller_profile_id     bigint        REFERENCES seller_profile (id) ON DELETE RESTRICT,
    sale_type             varchar(16)   NOT NULL,

    discount_type         varchar(16)   NOT NULL,
    discount_value        decimal(14,2) NOT NULL DEFAULT 0,
    max_discount_per_unit decimal(14,2),
    scope_type            varchar(16)   NOT NULL,
    scope_ids             bigint[]      NOT NULL DEFAULT '{}',
    conditions            text[]        NOT NULL DEFAULT '{}',
    min_unit_price        decimal(14,2),

    per_listing_quota     int,
    per_buyer_limit       int,
    budget_cap            decimal(14,2),
    subsidy_reserved      decimal(14,2) NOT NULL DEFAULT 0,

    banner_image_url      varchar(500),
    featured_on_home      boolean       NOT NULL DEFAULT false,
    home_sort             int,

    starts_at             timestamptz   NOT NULL,
    ends_at               timestamptz   NOT NULL,
    paused_at             timestamptz,
    paused_by             bigint        REFERENCES user_account (id) ON DELETE SET NULL,
    pause_reason          varchar(255),
    ended_at              timestamptz,
    created_by            bigint        REFERENCES user_account (id) ON DELETE SET NULL,
    version               int           NOT NULL DEFAULT 0,
    created_at            timestamptz   NOT NULL DEFAULT now(),
    updated_at            timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT uq_price_sale_slug UNIQUE (slug),
    CONSTRAINT ck_price_sale_slug CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    CONSTRAINT ck_price_sale_owner_type CHECK (owner_type IN ('PLATFORM', 'SELLER')),
    CONSTRAINT ck_price_sale_sale_type CHECK (sale_type IN ('FLASH_SALE', 'CATEGORY_SALE', 'SHOP_SALE')),
    CONSTRAINT ck_price_sale_discount_type CHECK (discount_type IN ('PERCENT', 'FIXED_OFF', 'FIXED_PRICE')),
    CONSTRAINT ck_price_sale_scope_type CHECK (scope_type IN
        ('ALL', 'GAME', 'CATEGORY', 'CARD_SET', 'SHOP', 'LISTINGS')),
    -- Pegasus runs flash and category sales over the whole market; a shop runs a
    -- shop sale over its own listings and nothing else.
    CONSTRAINT ck_price_sale_owner CHECK (
        (owner_type = 'PLATFORM' AND seller_profile_id IS NULL
            AND sale_type IN ('FLASH_SALE', 'CATEGORY_SALE')
            AND scope_type IN ('ALL', 'GAME', 'CATEGORY', 'CARD_SET'))
     OR (owner_type = 'SELLER' AND seller_profile_id IS NOT NULL
            AND sale_type = 'SHOP_SALE'
            AND scope_type IN ('SHOP', 'CATEGORY', 'LISTINGS'))),
    CONSTRAINT ck_price_sale_scope_ids CHECK (
        (scope_type IN ('ALL', 'SHOP', 'LISTINGS')) = (cardinality(scope_ids) = 0)),
    CONSTRAINT ck_price_sale_conditions CHECK (
        conditions <@ ARRAY['NM', 'LP', 'MP', 'HP', 'DMG', 'SEALED']::text[]),
    -- FIXED_PRICE has no value of its own: each row of price_sale_listing names its price.
    CONSTRAINT ck_price_sale_discount_value CHECK (
        (discount_type = 'PERCENT' AND discount_value BETWEEN 1 AND 90)
     OR (discount_type = 'FIXED_OFF' AND discount_value > 0)
     OR (discount_type = 'FIXED_PRICE' AND scope_type = 'LISTINGS' AND discount_value = 0)),
    CONSTRAINT ck_price_sale_amounts CHECK (
        (max_discount_per_unit IS NULL OR (max_discount_per_unit > 0 AND discount_type = 'PERCENT'))
        AND (min_unit_price IS NULL OR min_unit_price > 0)),
    CONSTRAINT ck_price_sale_limits CHECK (
        (per_listing_quota IS NULL OR per_listing_quota > 0)
        AND (per_buyer_limit IS NULL OR per_buyer_limit > 0)),
    -- Only Pegasus pays a subsidy, so only a Pegasus campaign has a budget for it.
    CONSTRAINT ck_price_sale_budget CHECK (
        (budget_cap IS NULL OR (budget_cap > 0 AND owner_type = 'PLATFORM'))
        AND subsidy_reserved >= 0),
    CONSTRAINT ck_price_sale_featured CHECK (NOT featured_on_home OR owner_type = 'PLATFORM'),
    CONSTRAINT ck_price_sale_period CHECK (ends_at > starts_at),
    CONSTRAINT ck_price_sale_flash_length CHECK (
        sale_type <> 'FLASH_SALE' OR ends_at - starts_at <= interval '72 hours'),
    CONSTRAINT ck_price_sale_pause CHECK (
        paused_at IS NOT NULL OR (paused_by IS NULL AND pause_reason IS NULL))
);

COMMENT ON COLUMN price_sale.slug IS
    'The /sale/{slug} page. Lower case words joined by hyphens, unique across Pegasus and every shop.';
COMMENT ON COLUMN price_sale.discount_value IS
    'Percent (1-90) for PERCENT, baht per card for FIXED_OFF, 0 for FIXED_PRICE.';
COMMENT ON COLUMN price_sale.scope_ids IS
    'game.id for GAME, catalog_category.id for CATEGORY, card_set.id for CARD_SET, empty for ALL, SHOP and LISTINGS.';
COMMENT ON COLUMN price_sale.conditions IS
    'Card conditions the sale covers; empty covers every condition.';
COMMENT ON COLUMN price_sale.per_listing_quota IS
    'Cards per listing a flash sale sells at the sale price; price_sale_listing.quota overrides it per listing.';
COMMENT ON COLUMN price_sale.subsidy_reserved IS
    'Subsidy held by checkouts so far. Reaching budget_cap ends the campaign as BUDGET_EXHAUSTED.';
COMMENT ON COLUMN price_sale.ended_at IS
    'Set when the owner or an admin ends the sale early; ends_at keeps the planned end.';

CREATE INDEX ix_price_sale_seller ON price_sale (seller_profile_id, created_at)
    WHERE seller_profile_id IS NOT NULL;
CREATE INDEX ix_price_sale_window ON price_sale (starts_at, ends_at) WHERE ended_at IS NULL;
CREATE INDEX ix_price_sale_home ON price_sale (home_sort, id) WHERE featured_on_home;

CREATE TRIGGER trg_price_sale_updated_at
    BEFORE UPDATE ON price_sale
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();


-- The listings a LISTINGS sale is made of, with each one's own price for
-- FIXED_PRICE, and the flash-sale quota per listing. sold_count is raised with a
-- guarded UPDATE at checkout so the quota holds under races.
CREATE TABLE price_sale_listing (
    sale_id    bigint        NOT NULL REFERENCES price_sale (id) ON DELETE CASCADE,
    listing_id bigint        NOT NULL REFERENCES listing (id) ON DELETE CASCADE,
    sale_price decimal(14,2),
    quota      int,
    sold_count int           NOT NULL DEFAULT 0,

    PRIMARY KEY (sale_id, listing_id),
    CONSTRAINT ck_price_sale_listing_price CHECK (sale_price IS NULL OR sale_price >= 1),
    CONSTRAINT ck_price_sale_listing_quota CHECK (
        sold_count >= 0 AND (quota IS NULL OR (quota > 0 AND sold_count <= quota)))
);

CREATE INDEX ix_price_sale_listing_listing ON price_sale_listing (listing_id);


-- The sale price each listing has right now, so search, the listing page and the
-- cart read one row instead of resolving every sale. Kept by SalePriceRefresher;
-- no row means the listing sells at listing.price.
CREATE TABLE listing_sale_price (
    listing_id      bigint        PRIMARY KEY REFERENCES listing (id) ON DELETE CASCADE,
    sale_id         bigint        NOT NULL REFERENCES price_sale (id) ON DELETE CASCADE,
    sale_price      decimal(14,2) NOT NULL,
    discount_amount decimal(14,2) NOT NULL,
    funded_by       varchar(16)   NOT NULL,
    ends_at         timestamptz   NOT NULL,
    quota_left      int,
    refreshed_at    timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT ck_listing_sale_price_amounts CHECK (sale_price >= 1 AND discount_amount > 0),
    CONSTRAINT ck_listing_sale_price_funded_by CHECK (funded_by IN ('PLATFORM', 'SELLER')),
    CONSTRAINT ck_listing_sale_price_quota CHECK (quota_left IS NULL OR quota_left > 0)
);

COMMENT ON COLUMN listing_sale_price.discount_amount IS
    'listing.price minus sale_price at the last refresh, per card.';

CREATE INDEX ix_listing_sale_price_sale ON listing_sale_price (sale_id);


-- What each card sold for against its asking price, and who paid the difference.
-- unit_price stays what the buyer paid per card before any code. Orders placed
-- before this sold at list price: list_price = unit_price, no sale.
ALTER TABLE order_item
    ADD COLUMN list_price     decimal(14,2),
    ADD COLUMN sale_discount  decimal(14,2) NOT NULL DEFAULT 0,
    ADD COLUMN sale_id        bigint        REFERENCES price_sale (id) ON DELETE RESTRICT,
    ADD COLUMN sale_funded_by varchar(16);

UPDATE order_item SET list_price = unit_price;

ALTER TABLE order_item
    ALTER COLUMN list_price SET NOT NULL,
    ADD CONSTRAINT ck_order_item_sale CHECK (
        sale_discount >= 0
        AND unit_price = list_price - sale_discount
        AND (sale_funded_by IS NULL OR sale_funded_by IN ('PLATFORM', 'SELLER'))
        AND (sale_id IS NULL) = (sale_funded_by IS NULL)
        AND (sale_id IS NOT NULL OR sale_discount = 0));

COMMENT ON COLUMN order_item.list_price IS
    'listing.price per card at checkout; the struck-through price.';
COMMENT ON COLUMN order_item.sale_discount IS
    'Per card. list_price - sale_discount = unit_price.';

CREATE INDEX ix_order_item_sale ON order_item (sale_id) WHERE sale_id IS NOT NULL;

-- Writers that know nothing of sales (seeds, and checkout until TCG-680) leave
-- list_price out; the card then sold at its asking price.
CREATE FUNCTION order_item_default_list_price() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.list_price IS NULL THEN
        NEW.list_price := NEW.unit_price + NEW.sale_discount;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_order_item_default_list_price
    BEFORE INSERT ON order_item
    FOR EACH ROW EXECUTE FUNCTION order_item_default_list_price();

-- Pegasus pays a shop the discount of a Pegasus campaign when the money is
-- released, kept apart from PROMO_SUBSIDY so the two costs report separately.
ALTER TABLE ledger_entry DROP CONSTRAINT ck_ledger_entry_type;
ALTER TABLE ledger_entry ADD CONSTRAINT ck_ledger_entry_type CHECK (entry_type IN
    ('PAYMENT_IN', 'ESCROW_HOLD', 'ESCROW_RELEASE', 'COMMISSION_FEE',
     'SELLER_EARNING', 'REFUND_OUT', 'PAYOUT_OUT', 'ADJUSTMENT', 'PROMO_SUBSIDY', 'SALE_SUBSIDY'));
