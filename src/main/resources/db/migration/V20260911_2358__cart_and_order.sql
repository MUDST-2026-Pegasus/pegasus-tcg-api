-- Schema sprint 4/7 — from basket to parcel.
--
-- One basket can hold cards from several sellers, so one sales_order (= one
-- payment) fans out into one seller_order per seller, each with its own status,
-- shipping fee, commission and payout [RQ-11,12,13,14].
--
-- Two foreign keys on seller_order point at tables created in V7 and are added
-- there: commission_rule_id and payout_id.

CREATE TABLE cart (
    id          bigint      PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    user_id     bigint      REFERENCES user_account (id) ON DELETE CASCADE,
    session_key varchar(128),
    currency    char(3)     NOT NULL DEFAULT 'THB',
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    expires_at  timestamptz,

    CONSTRAINT uq_cart_user UNIQUE (user_id),
    CONSTRAINT uq_cart_session UNIQUE (session_key),
    CONSTRAINT ck_cart_owner CHECK (user_id IS NOT NULL OR session_key IS NOT NULL)
);

COMMENT ON COLUMN cart.session_key IS
    'Signed-out basket, merged into the user''s basket on sign-in. Checkout still requires an account.';

CREATE TRIGGER trg_cart_updated_at
    BEFORE UPDATE ON cart
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();


CREATE TABLE cart_item (
    id                 bigint        PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    cart_id            bigint        NOT NULL REFERENCES cart (id) ON DELETE CASCADE,
    listing_id         bigint        NOT NULL REFERENCES listing (id) ON DELETE CASCADE,
    quantity           int           NOT NULL DEFAULT 1,
    unit_price_at_add  decimal(14,2) NOT NULL,
    added_at           timestamptz   NOT NULL DEFAULT now(),
    updated_at         timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT ck_cart_item_quantity CHECK (quantity > 0),
    CONSTRAINT ck_cart_item_price CHECK (unit_price_at_add > 0)
);

COMMENT ON COLUMN cart_item.unit_price_at_add IS
    'The price when it went in, so checkout can say "this price changed" [CR-3].';
COMMENT ON TABLE cart_item IS
    'Stock is checked at checkout, not on add: holding stock for an idle basket starves real buyers.';

CREATE UNIQUE INDEX ux_cart_item ON cart_item (cart_id, listing_id);
CREATE INDEX ix_cart_item_listing ON cart_item (listing_id);

CREATE TRIGGER trg_cart_item_updated_at
    BEFORE UPDATE ON cart_item
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();


-- The buyer's order: one payment, however many sellers are involved [RQ-11].
CREATE TABLE sales_order (
    id                        bigint        PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    order_number              varchar(32)   NOT NULL,
    buyer_id                  bigint        NOT NULL REFERENCES user_account (id) ON DELETE RESTRICT,
    status                    varchar(32)   NOT NULL DEFAULT 'PENDING_PAYMENT',
    currency                  char(3)       NOT NULL DEFAULT 'THB',
    items_subtotal            decimal(14,2) NOT NULL DEFAULT 0,
    shipping_total            decimal(14,2) NOT NULL DEFAULT 0,
    discount_total            decimal(14,2) NOT NULL DEFAULT 0,
    grand_total               decimal(14,2) NOT NULL DEFAULT 0,

    shipping_address_id       bigint        REFERENCES address (id) ON DELETE SET NULL,
    shipping_address_snapshot jsonb         NOT NULL,
    buyer_note                varchar(500),

    idempotency_key           varchar(64),
    placed_at                 timestamptz   NOT NULL DEFAULT now(),
    paid_at                   timestamptz,
    completed_at              timestamptz,
    cancelled_at              timestamptz,
    updated_at                timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT uq_sales_order_number UNIQUE (order_number),
    CONSTRAINT uq_sales_order_idempotency UNIQUE (idempotency_key),
    CONSTRAINT ck_sales_order_status CHECK (status IN
        ('PENDING_PAYMENT', 'PAID', 'PARTIALLY_COMPLETED', 'COMPLETED', 'CANCELLED', 'REFUNDED')),
    CONSTRAINT ck_sales_order_totals CHECK (
        items_subtotal >= 0 AND shipping_total >= 0 AND discount_total >= 0
        AND grand_total = items_subtotal + shipping_total - discount_total),
    CONSTRAINT ck_sales_order_address_snapshot CHECK (
        jsonb_typeof(shipping_address_snapshot) = 'object')
);

COMMENT ON COLUMN sales_order.shipping_address_snapshot IS
    'The address as it read when the order was placed; editing the address book never rewrites history.';
COMMENT ON COLUMN sales_order.idempotency_key IS
    'Stops a double-tapped checkout from becoming two orders.';

CREATE INDEX ix_sales_order_buyer ON sales_order (buyer_id, placed_at);
CREATE INDEX ix_sales_order_status ON sales_order (status, placed_at);

CREATE TRIGGER trg_sales_order_updated_at
    BEFORE UPDATE ON sales_order
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();


-- The heart of multi-vendor. [CR-7]'s pending/fulfilled maps to:
--   pending   = PAID | PREPARING
--   fulfilled = SHIPPED and beyond
CREATE TABLE seller_order (
    id                      bigint        PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    sales_order_id          bigint        NOT NULL REFERENCES sales_order (id) ON DELETE RESTRICT,
    seller_profile_id       bigint        NOT NULL REFERENCES seller_profile (id) ON DELETE RESTRICT,
    seller_order_number     varchar(40)   NOT NULL,
    status                  varchar(32)   NOT NULL DEFAULT 'PENDING_PAYMENT',

    items_subtotal          decimal(14,2) NOT NULL DEFAULT 0,
    shipping_fee            decimal(14,2) NOT NULL DEFAULT 0,
    discount_amount         decimal(14,2) NOT NULL DEFAULT 0,
    grand_total             decimal(14,2) NOT NULL DEFAULT 0,

    commission_rule_id      bigint,
    commission_rate_percent decimal(6,3)  NOT NULL DEFAULT 0,
    commission_amount       decimal(14,2) NOT NULL DEFAULT 0,
    seller_net_amount       decimal(14,2) NOT NULL DEFAULT 0,

    shipping_option_id      bigint        REFERENCES seller_shipping_option (id) ON DELETE SET NULL,
    payout_id               bigint,

    accepted_at             timestamptz,
    shipped_at              timestamptz,
    delivered_at            timestamptz,
    auto_complete_at        timestamptz,
    completed_at            timestamptz,
    cancelled_at            timestamptz,
    cancel_reason           varchar(255),
    created_at              timestamptz   NOT NULL DEFAULT now(),
    updated_at              timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT uq_seller_order_number UNIQUE (seller_order_number),
    CONSTRAINT ck_seller_order_status CHECK (status IN
        ('PENDING_PAYMENT', 'PAID', 'PREPARING', 'SHIPPED', 'DELIVERED', 'COMPLETED',
         'CANCELLED', 'RETURN_REQUESTED', 'RETURNED', 'REFUNDED')),
    CONSTRAINT ck_seller_order_totals CHECK (
        items_subtotal >= 0 AND shipping_fee >= 0 AND discount_amount >= 0
        AND grand_total = items_subtotal + shipping_fee - discount_amount),
    CONSTRAINT ck_seller_order_commission CHECK (
        commission_amount >= 0
        AND seller_net_amount = grand_total - commission_amount)
);

COMMENT ON COLUMN seller_order.commission_rate_percent IS
    'Snapshot of the rate used. Changing the rule later never rewrites this order [RQ-14].';
COMMENT ON COLUMN seller_order.auto_complete_at IS
    'delivered_at + N days. Escrow releases on its own if the buyer never confirms [RQ-11].';

CREATE INDEX ix_seller_order_seller ON seller_order (seller_profile_id, status, created_at);
CREATE INDEX ix_seller_order_parent ON seller_order (sales_order_id);
CREATE INDEX ix_seller_order_auto_complete ON seller_order (status, auto_complete_at);
CREATE INDEX ix_seller_order_payout ON seller_order (payout_id);

CREATE TRIGGER trg_seller_order_updated_at
    BEFORE UPDATE ON seller_order
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();


-- Everything financial is frozen here at the moment of sale, so a later price
-- edit or a deleted listing cannot distort an old order or its profit [CR-6].
CREATE TABLE order_item (
    id                      bigint        PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    seller_order_id         bigint        NOT NULL REFERENCES seller_order (id) ON DELETE CASCADE,
    listing_id              bigint        REFERENCES listing (id) ON DELETE SET NULL,
    catalog_variant_id      bigint        NOT NULL REFERENCES catalog_variant (id) ON DELETE RESTRICT,
    quantity                int           NOT NULL,
    unit_price              decimal(14,2) NOT NULL,
    line_total              decimal(14,2) NOT NULL,
    unit_cost_snapshot      decimal(14,4) NOT NULL DEFAULT 0,

    product_name_snapshot   varchar(255)  NOT NULL,
    variant_label_snapshot  varchar(255)  NOT NULL,
    condition_snapshot      varchar(16)   NOT NULL,
    game_name_snapshot      varchar(100),
    image_key_snapshot      varchar(500),

    created_at              timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT ck_order_item_quantity CHECK (quantity > 0),
    CONSTRAINT ck_order_item_line_total CHECK (line_total = unit_price * quantity),
    CONSTRAINT ck_order_item_condition CHECK (condition_snapshot IN
        ('NM', 'LP', 'MP', 'HP', 'DMG', 'SEALED'))
);

COMMENT ON COLUMN order_item.unit_cost_snapshot IS
    'Weighted average cost at the time of sale, so profit on this order never moves [RQ-7][CR-6].';
COMMENT ON COLUMN order_item.variant_label_snapshot IS
    'Rendered form, e.g. "EN / Foil / 1st Edition".';

CREATE INDEX ix_order_item_suborder ON order_item (seller_order_id);
CREATE INDEX ix_order_item_variant ON order_item (catalog_variant_id);
CREATE INDEX ix_order_item_listing ON order_item (listing_id);


-- Which exact card went into which order [RQ-9]. Written when stock is reserved,
-- not when it ships, alongside flipping the unit to RESERVED.
--
-- A card can be sold, returned and sold again, so this is a table rather than a
-- column on listing_unit: a single column would be overwritten and the first
-- sale would vanish.
CREATE TABLE order_item_unit (
    order_item_id   bigint      NOT NULL REFERENCES order_item (id) ON DELETE CASCADE,
    listing_unit_id bigint      NOT NULL REFERENCES listing_unit (id) ON DELETE RESTRICT,
    created_at      timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (order_item_id, listing_unit_id)
);

COMMENT ON TABLE order_item_unit IS
    'Service layer invariant: count per order_item must equal order_item.quantity.';

CREATE INDEX ix_order_item_unit_unit ON order_item_unit (listing_unit_id);


CREATE TABLE seller_order_status_history (
    id              bigint       PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    seller_order_id bigint       NOT NULL REFERENCES seller_order (id) ON DELETE CASCADE,
    from_status     varchar(32),
    to_status       varchar(32)  NOT NULL,
    changed_by      bigint       REFERENCES user_account (id) ON DELETE SET NULL,
    note            varchar(500),
    created_at      timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT ck_order_status_history_to CHECK (to_status IN
        ('PENDING_PAYMENT', 'PAID', 'PREPARING', 'SHIPPED', 'DELIVERED', 'COMPLETED',
         'CANCELLED', 'RETURN_REQUESTED', 'RETURNED', 'REFUNDED')),
    CONSTRAINT ck_order_status_history_from CHECK (from_status IS NULL OR from_status IN
        ('PENDING_PAYMENT', 'PAID', 'PREPARING', 'SHIPPED', 'DELIVERED', 'COMPLETED',
         'CANCELLED', 'RETURN_REQUESTED', 'RETURNED', 'REFUNDED'))
);

COMMENT ON TABLE seller_order_status_history IS
    'Audit trail of the state machine [CR-7]: answers disputes, and tests read it directly.';
COMMENT ON COLUMN seller_order_status_history.changed_by IS
    'NULL means the system moved it, not a person.';

CREATE INDEX ix_order_status_history_order
    ON seller_order_status_history (seller_order_id, created_at);


-- Sellers type the tracking number themselves; no carrier API [CR-4][RQ-12,13].
-- One sub-order can go out as several parcels.
CREATE TABLE shipment (
    id                      bigint       PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    seller_order_id         bigint       NOT NULL REFERENCES seller_order (id) ON DELETE CASCADE,
    carrier_code            varchar(32),
    carrier_name            varchar(100) NOT NULL,
    tracking_number         varchar(64)  NOT NULL,
    status                  varchar(32)  NOT NULL DEFAULT 'LABEL_CREATED',
    shipped_at              timestamptz,
    estimated_delivery_date date,
    delivered_at            timestamptz,
    proof_image_key         varchar(500),
    created_by              bigint       NOT NULL REFERENCES user_account (id) ON DELETE RESTRICT,
    created_at              timestamptz  NOT NULL DEFAULT now(),
    updated_at              timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT ck_shipment_status CHECK (status IN
        ('LABEL_CREATED', 'IN_TRANSIT', 'OUT_FOR_DELIVERY', 'DELIVERED',
         'FAILED', 'RETURNED_TO_SENDER'))
);

CREATE INDEX ix_shipment_order ON shipment (seller_order_id);
CREATE INDEX ix_shipment_tracking ON shipment (tracking_number);

CREATE TRIGGER trg_shipment_updated_at
    BEFORE UPDATE ON shipment
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
