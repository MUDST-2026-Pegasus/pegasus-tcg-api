-- Discount codes [TCG-566]. Pegasus issues PLATFORM codes and pays for them;
-- a seller issues SELLER codes for their own shop and pays for those. An order
-- takes at most one Pegasus code and one code per shop. Status (scheduled,
-- active, paused, used up, expired) is worked out from these columns, never stored.
CREATE TABLE promotion (
    id                   bigint        PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    code                 varchar(20)   NOT NULL,
    name                 varchar(120)  NOT NULL,
    description          varchar(500),
    owner_type           varchar(16)   NOT NULL,
    seller_profile_id    bigint        REFERENCES seller_profile (id) ON DELETE RESTRICT,

    discount_type        varchar(16)   NOT NULL,
    discount_value       decimal(14,2) NOT NULL DEFAULT 0,
    max_discount         decimal(14,2),
    min_spend            decimal(14,2) NOT NULL DEFAULT 0,
    scope_type           varchar(16)   NOT NULL DEFAULT 'ALL',
    scope_ids            bigint[]      NOT NULL DEFAULT '{}',
    visibility           varchar(16)   NOT NULL DEFAULT 'PUBLIC',
    new_buyer_only       boolean       NOT NULL DEFAULT false,

    usage_limit_total    int,
    usage_limit_per_user int           NOT NULL DEFAULT 1,
    used_count           int           NOT NULL DEFAULT 0,

    claimable            boolean       NOT NULL DEFAULT false,
    claim_required       boolean       NOT NULL DEFAULT false,
    claim_limit          int,
    claimed_count        int           NOT NULL DEFAULT 0,
    featured_on_home     boolean       NOT NULL DEFAULT false,
    home_sort            int,

    starts_at            timestamptz   NOT NULL,
    ends_at              timestamptz   NOT NULL,
    paused_at            timestamptz,
    paused_by            bigint        REFERENCES user_account (id) ON DELETE SET NULL,
    pause_reason         varchar(255),
    created_by           bigint        REFERENCES user_account (id) ON DELETE SET NULL,
    version              int           NOT NULL DEFAULT 0,
    created_at           timestamptz   NOT NULL DEFAULT now(),
    updated_at           timestamptz   NOT NULL DEFAULT now(),

    -- One namespace for every shop, so a typed code always says whose it is.
    CONSTRAINT uq_promotion_code UNIQUE (code),
    CONSTRAINT ck_promotion_code CHECK (code ~ '^[A-Z0-9]{4,20}$'),
    CONSTRAINT ck_promotion_owner_type CHECK (owner_type IN ('PLATFORM', 'SELLER')),
    CONSTRAINT ck_promotion_discount_type CHECK (discount_type IN ('PERCENT', 'FIXED', 'FREE_SHIPPING')),
    CONSTRAINT ck_promotion_scope_type CHECK (scope_type IN ('ALL', 'GAME', 'CATEGORY')),
    CONSTRAINT ck_promotion_visibility CHECK (visibility IN ('PUBLIC', 'PRIVATE')),
    -- A shop code covers everything in that shop and nothing else.
    CONSTRAINT ck_promotion_owner CHECK (
        (owner_type = 'PLATFORM' AND seller_profile_id IS NULL)
     OR (owner_type = 'SELLER' AND seller_profile_id IS NOT NULL AND scope_type = 'ALL')),
    CONSTRAINT ck_promotion_scope_ids CHECK (
        (scope_type = 'ALL') = (cardinality(scope_ids) = 0)),
    -- FREE_SHIPPING has no value of its own: max_discount caps it per shop.
    CONSTRAINT ck_promotion_discount_value CHECK (
        (discount_type = 'PERCENT' AND discount_value BETWEEN 1 AND 90)
     OR (discount_type = 'FIXED' AND discount_value > 0)
     OR (discount_type = 'FREE_SHIPPING' AND owner_type = 'PLATFORM' AND discount_value = 0)),
    CONSTRAINT ck_promotion_amounts CHECK (
        (max_discount IS NULL OR max_discount > 0) AND min_spend >= 0),
    CONSTRAINT ck_promotion_usage CHECK (
        (usage_limit_total IS NULL OR usage_limit_total > 0)
        AND usage_limit_per_user > 0
        AND used_count >= 0
        AND (usage_limit_total IS NULL OR used_count <= usage_limit_total)),
    CONSTRAINT ck_promotion_claim CHECK (
        (claimable OR NOT claim_required)
        AND claimed_count >= 0
        AND (claim_limit IS NULL OR (claim_limit > 0 AND claim_limit >= claimed_count))),
    CONSTRAINT ck_promotion_featured CHECK (
        NOT featured_on_home
        OR (owner_type = 'PLATFORM' AND claimable AND visibility = 'PUBLIC')),
    CONSTRAINT ck_promotion_period CHECK (ends_at > starts_at),
    CONSTRAINT ck_promotion_pause CHECK (
        paused_at IS NOT NULL OR (paused_by IS NULL AND pause_reason IS NULL))
);

COMMENT ON COLUMN promotion.code IS
    'Upper case A-Z 0-9, 4 to 20 characters; unique across Pegasus and every shop.';
COMMENT ON COLUMN promotion.discount_value IS
    'Percent (1-90) for PERCENT, baht for FIXED, 0 for FREE_SHIPPING.';
COMMENT ON COLUMN promotion.max_discount IS
    'Cap in baht: on the whole discount for PERCENT, per shop on shipping for FREE_SHIPPING.';
COMMENT ON COLUMN promotion.scope_ids IS
    'game.id values for GAME, catalog_category.id values for CATEGORY, empty for ALL.';
COMMENT ON COLUMN promotion.used_count IS
    'Redemptions RESERVED or CONSUMED. Raised with a guarded UPDATE so the limit holds under races.';
COMMENT ON COLUMN promotion.claimed_count IS
    'Rows in promotion_claim. Claiming a code does not use it up.';

CREATE INDEX ix_promotion_seller ON promotion (seller_profile_id, created_at)
    WHERE seller_profile_id IS NOT NULL;
CREATE INDEX ix_promotion_window ON promotion (owner_type, starts_at, ends_at);
CREATE INDEX ix_promotion_home ON promotion (home_sort, id) WHERE featured_on_home;


-- A code applied to an order. Held at checkout, consumed once the order is paid,
-- released when it times out or is cancelled unpaid. A code applies to an order
-- once: a Pegasus code over several shops is one row with seller_order_id NULL.
CREATE TABLE promotion_redemption (
    id              bigint        PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    promotion_id    bigint        NOT NULL REFERENCES promotion (id) ON DELETE RESTRICT,
    buyer_id        bigint        NOT NULL REFERENCES user_account (id) ON DELETE RESTRICT,
    sales_order_id  bigint        NOT NULL REFERENCES sales_order (id) ON DELETE RESTRICT,
    seller_order_id bigint        REFERENCES seller_order (id) ON DELETE RESTRICT,
    amount          decimal(14,2) NOT NULL,
    status          varchar(16)   NOT NULL DEFAULT 'RESERVED',
    created_at      timestamptz   NOT NULL DEFAULT now(),
    updated_at      timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT uq_promotion_redemption_order UNIQUE (promotion_id, sales_order_id),
    CONSTRAINT ck_promotion_redemption_amount CHECK (amount > 0),
    CONSTRAINT ck_promotion_redemption_status CHECK (status IN ('RESERVED', 'CONSUMED', 'RELEASED'))
);

CREATE INDEX ix_promotion_redemption_buyer ON promotion_redemption (promotion_id, buyer_id, status);
CREATE INDEX ix_promotion_redemption_sales_order ON promotion_redemption (sales_order_id);


-- Codes a user has collected into "My coupons". Collecting one is free and does
-- not count against its usage limit; only claim_limit caps how many are handed out.
CREATE TABLE promotion_claim (
    id           bigint      PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    promotion_id bigint      NOT NULL REFERENCES promotion (id) ON DELETE CASCADE,
    user_id      bigint      NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    source       varchar(16) NOT NULL,
    claimed_at   timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT uq_promotion_claim_user UNIQUE (promotion_id, user_id),
    CONSTRAINT ck_promotion_claim_source CHECK (source IN ('HOME', 'STORE', 'WALLET_INPUT', 'CART'))
);

CREATE INDEX ix_promotion_claim_user ON promotion_claim (user_id, claimed_at);


-- Who pays for the discount on a shop's part of the order. The shop's own code
-- comes off its earnings; a Pegasus code is paid back to the shop at release
-- as a PROMO_SUBSIDY ledger entry. Orders placed before this have both at 0.
ALTER TABLE seller_order
    ADD COLUMN seller_discount_amount   decimal(14,2) NOT NULL DEFAULT 0,
    ADD COLUMN platform_discount_amount decimal(14,2) NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_seller_order_discount_split CHECK (
        seller_discount_amount >= 0 AND platform_discount_amount >= 0
        AND discount_amount = seller_discount_amount + platform_discount_amount);

-- Each card's share of the item discounts, so a partial return refunds what
-- the buyer actually paid for that card. Shipping discounts are not spread here.
ALTER TABLE order_item
    ADD COLUMN discount_allocated decimal(14,2) NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_order_item_discount_allocated CHECK (
        discount_allocated >= 0 AND discount_allocated <= line_total);

ALTER TABLE ledger_entry DROP CONSTRAINT ck_ledger_entry_type;
ALTER TABLE ledger_entry ADD CONSTRAINT ck_ledger_entry_type CHECK (entry_type IN
    ('PAYMENT_IN', 'ESCROW_HOLD', 'ESCROW_RELEASE', 'COMMISSION_FEE',
     'SELLER_EARNING', 'REFUND_OUT', 'PAYOUT_OUT', 'ADJUSTMENT', 'PROMO_SUBSIDY'));
