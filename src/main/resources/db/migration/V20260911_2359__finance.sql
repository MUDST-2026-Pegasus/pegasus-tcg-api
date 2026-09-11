-- Schema sprint 5/7 — money.
--
-- Money never goes straight to a seller. It lands in the platform wallet, sits
-- in escrow until the buyer confirms receipt, and only then moves across, minus
-- commission [RQ-11]. Every movement is a ledger row, so the books can be
-- replayed from scratch.
--
-- Closes the two forward references left open by V6.

CREATE TABLE payment (
    id              bigint        PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    sales_order_id  bigint        NOT NULL REFERENCES sales_order (id) ON DELETE RESTRICT,
    method          varchar(32)   NOT NULL,
    status          varchar(32)   NOT NULL DEFAULT 'PENDING',
    amount          decimal(14,2) NOT NULL,
    currency        char(3)       NOT NULL DEFAULT 'THB',
    reference_code  varchar(64),
    slip_image_key  varchar(500),
    idempotency_key varchar(64),
    paid_at         timestamptz,
    verified_by     bigint        REFERENCES user_account (id) ON DELETE SET NULL,
    verified_at     timestamptz,
    failure_reason  varchar(255),
    created_at      timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT uq_payment_idempotency UNIQUE (idempotency_key),
    CONSTRAINT ck_payment_method CHECK (method IN
        ('BANK_TRANSFER', 'MOCK_GATEWAY', 'WALLET', 'CASH_ON_DELIVERY')),
    CONSTRAINT ck_payment_status CHECK (status IN
        ('PENDING', 'AWAITING_VERIFICATION', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT ck_payment_amount CHECK (amount > 0)
);

COMMENT ON COLUMN payment.method IS
    'BANK_TRANSFER is a slip upload verified by an admin; no external gateway is required [CR-4].';

CREATE INDEX ix_payment_order ON payment (sales_order_id);
CREATE INDEX ix_payment_status ON payment (status, created_at);


CREATE TABLE wallet (
    id                bigint        PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    kind              varchar(32)   NOT NULL,
    user_id           bigint        REFERENCES user_account (id) ON DELETE RESTRICT,
    currency          char(3)       NOT NULL DEFAULT 'THB',
    balance_available decimal(16,2) NOT NULL DEFAULT 0,
    balance_pending   decimal(16,2) NOT NULL DEFAULT 0,
    version           int           NOT NULL DEFAULT 0,
    created_at        timestamptz   NOT NULL DEFAULT now(),
    updated_at        timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT uq_wallet_user UNIQUE (user_id),
    CONSTRAINT ck_wallet_kind CHECK (kind IN ('PLATFORM', 'SELLER')),
    CONSTRAINT ck_wallet_owner CHECK (
        (kind = 'PLATFORM' AND user_id IS NULL)
        OR (kind = 'SELLER' AND user_id IS NOT NULL))
);

COMMENT ON COLUMN wallet.balance_available IS
    'Withdrawable. Cached rollup of ledger_entry — regression test #1 compares the two.';
COMMENT ON COLUMN wallet.balance_pending IS 'Still sitting in escrow.';

CREATE INDEX ix_wallet_kind ON wallet (kind);

-- There is exactly one platform wallet, and the database is where that is true.
CREATE UNIQUE INDEX ux_wallet_platform ON wallet ((kind)) WHERE kind = 'PLATFORM';

CREATE TRIGGER trg_wallet_updated_at
    BEFORE UPDATE ON wallet
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();


-- Append-only, double entry. A mistake is corrected with a reversing entry,
-- never by editing a row — which is why the trigger below refuses both.
CREATE TABLE ledger_entry (
    id             bigint        PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    wallet_id      bigint        NOT NULL REFERENCES wallet (id) ON DELETE RESTRICT,
    entry_type     varchar(32)   NOT NULL,
    direction      varchar(8)    NOT NULL,
    amount         decimal(16,2) NOT NULL,
    balance_after  decimal(16,2) NOT NULL,
    reference_type varchar(32)   NOT NULL,
    reference_id   bigint        NOT NULL,
    memo           varchar(255),
    created_at     timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT ck_ledger_entry_type CHECK (entry_type IN
        ('PAYMENT_IN', 'ESCROW_HOLD', 'ESCROW_RELEASE', 'COMMISSION_FEE',
         'SELLER_EARNING', 'REFUND_OUT', 'PAYOUT_OUT', 'ADJUSTMENT')),
    CONSTRAINT ck_ledger_direction CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ck_ledger_amount CHECK (amount > 0),
    CONSTRAINT ck_ledger_reference_type CHECK (reference_type IN
        ('PAYMENT', 'SELLER_ORDER', 'REFUND', 'PAYOUT', 'ADJUSTMENT'))
);

COMMENT ON TABLE ledger_entry IS
    'Grows fastest of any table. When volume warrants it, convert to PARTITION BY RANGE (created_at), monthly.';

CREATE INDEX ix_ledger_wallet ON ledger_entry (wallet_id, created_at);
CREATE INDEX ix_ledger_reference ON ledger_entry (reference_type, reference_id);
CREATE INDEX ix_ledger_type ON ledger_entry (entry_type, created_at);

CREATE FUNCTION ledger_entry_is_append_only() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'ledger_entry is append-only: post a reversing entry instead of %', TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER trg_ledger_entry_append_only
    BEFORE UPDATE OR DELETE ON ledger_entry
    FOR EACH ROW EXECUTE FUNCTION ledger_entry_is_append_only();


-- Why the money waits: paying a seller on day one turns a refund into debt
-- collection.
CREATE TABLE escrow_hold (
    id              bigint        PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    seller_order_id bigint        NOT NULL REFERENCES seller_order (id) ON DELETE RESTRICT,
    wallet_id       bigint        NOT NULL REFERENCES wallet (id) ON DELETE RESTRICT,
    amount          decimal(14,2) NOT NULL,
    released_amount decimal(14,2) NOT NULL DEFAULT 0,
    refunded_amount decimal(14,2) NOT NULL DEFAULT 0,
    status          varchar(32)   NOT NULL DEFAULT 'HELD',
    held_at         timestamptz   NOT NULL DEFAULT now(),
    release_due_at  timestamptz,
    released_at     timestamptz,

    CONSTRAINT uq_escrow_hold_seller_order UNIQUE (seller_order_id),
    CONSTRAINT ck_escrow_status CHECK (status IN
        ('HELD', 'PARTIALLY_RELEASED', 'RELEASED', 'REFUNDED', 'CANCELLED')),
    CONSTRAINT ck_escrow_amounts CHECK (
        amount > 0 AND released_amount >= 0 AND refunded_amount >= 0
        AND released_amount + refunded_amount <= amount)
);

COMMENT ON COLUMN escrow_hold.wallet_id IS 'The PLATFORM wallet holding the money.';
COMMENT ON COLUMN escrow_hold.release_due_at IS
    'Past this point the money is released even if the buyer never confirms.';

CREATE INDEX ix_escrow_auto_release ON escrow_hold (status, release_due_at);


-- Fee rules, resolved most-specific-first: SELLER > CATEGORY > GAME > GLOBAL,
-- highest priority wins within a scope [RQ-14].
CREATE TABLE commission_rule (
    id                bigint        PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    scope             varchar(32)   NOT NULL,
    game_id           smallint      REFERENCES game (id) ON DELETE RESTRICT,
    category_id       int           REFERENCES catalog_category (id) ON DELETE RESTRICT,
    seller_profile_id bigint        REFERENCES seller_profile (id) ON DELETE CASCADE,
    rate_percent      decimal(6,3)  NOT NULL DEFAULT 0,
    fixed_fee         decimal(14,2) NOT NULL DEFAULT 0,
    min_fee           decimal(14,2),
    max_fee           decimal(14,2),
    priority          smallint      NOT NULL DEFAULT 0,
    effective_from    timestamptz   NOT NULL DEFAULT now(),
    effective_to      timestamptz,
    is_active         boolean       NOT NULL DEFAULT true,
    created_by        bigint        REFERENCES user_account (id) ON DELETE SET NULL,
    created_at        timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT ck_commission_scope CHECK (scope IN ('GLOBAL', 'GAME', 'CATEGORY', 'SELLER')),
    CONSTRAINT ck_commission_rate CHECK (rate_percent >= 0 AND fixed_fee >= 0),
    CONSTRAINT ck_commission_fee_band CHECK (
        min_fee IS NULL OR max_fee IS NULL OR min_fee <= max_fee),
    CONSTRAINT ck_commission_period CHECK (
        effective_to IS NULL OR effective_from < effective_to),
    -- The scope decides which reference is filled in; the others must be empty.
    CONSTRAINT ck_commission_scope_target CHECK (
        (scope = 'GLOBAL'   AND game_id IS NULL     AND category_id IS NULL AND seller_profile_id IS NULL)
     OR (scope = 'GAME'     AND game_id IS NOT NULL AND category_id IS NULL AND seller_profile_id IS NULL)
     OR (scope = 'CATEGORY' AND game_id IS NULL     AND category_id IS NOT NULL AND seller_profile_id IS NULL)
     OR (scope = 'SELLER'   AND game_id IS NULL     AND category_id IS NULL AND seller_profile_id IS NOT NULL))
);

CREATE INDEX ix_commission_rule_lookup ON commission_rule (scope, is_active, priority);
CREATE INDEX ix_commission_rule_period ON commission_rule (effective_from, effective_to);


-- The fee actually taken, with the arithmetic frozen alongside it, so changing
-- a rule tomorrow cannot rewrite what was charged today.
CREATE TABLE commission_charge (
    id                 bigint        PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    seller_order_id    bigint        NOT NULL REFERENCES seller_order (id) ON DELETE CASCADE,
    commission_rule_id bigint        REFERENCES commission_rule (id) ON DELETE SET NULL,
    base_amount        decimal(14,2) NOT NULL,
    rate_percent       decimal(6,3)  NOT NULL,
    fixed_fee          decimal(14,2) NOT NULL DEFAULT 0,
    charged_amount     decimal(14,2) NOT NULL,
    calculated_at      timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT uq_commission_charge_seller_order UNIQUE (seller_order_id),
    CONSTRAINT ck_commission_charge_amounts CHECK (
        base_amount >= 0 AND rate_percent >= 0 AND fixed_fee >= 0 AND charged_amount >= 0)
);

COMMENT ON COLUMN commission_charge.base_amount IS
    'What the percentage was applied to: items_subtotal, shipping excluded.';


CREATE TABLE payout (
    id                 bigint        PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    seller_profile_id  bigint        NOT NULL REFERENCES seller_profile (id) ON DELETE RESTRICT,
    wallet_id          bigint        NOT NULL REFERENCES wallet (id) ON DELETE RESTRICT,
    payout_account_id  bigint        REFERENCES seller_payout_account (id) ON DELETE SET NULL,
    amount             decimal(14,2) NOT NULL,
    currency           char(3)       NOT NULL DEFAULT 'THB',
    status             varchar(32)   NOT NULL DEFAULT 'REQUESTED',
    requested_at       timestamptz   NOT NULL DEFAULT now(),
    approved_by        bigint        REFERENCES user_account (id) ON DELETE SET NULL,
    approved_at        timestamptz,
    paid_at            timestamptz,
    transfer_reference varchar(64),
    slip_image_key     varchar(500),
    rejection_reason   varchar(255),

    CONSTRAINT ck_payout_status CHECK (status IN
        ('REQUESTED', 'APPROVED', 'PROCESSING', 'PAID', 'REJECTED', 'FAILED')),
    CONSTRAINT ck_payout_amount CHECK (amount > 0)
);

CREATE INDEX ix_payout_seller ON payout (seller_profile_id, status);
CREATE INDEX ix_payout_queue ON payout (status, requested_at);


CREATE TABLE refund (
    id                bigint        PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    sales_order_id    bigint        NOT NULL REFERENCES sales_order (id) ON DELETE RESTRICT,
    seller_order_id   bigint        REFERENCES seller_order (id) ON DELETE RESTRICT,
    return_request_id bigint,
    amount            decimal(14,2) NOT NULL,
    reason            varchar(255)  NOT NULL,
    status            varchar(32)   NOT NULL DEFAULT 'PENDING',
    method            varchar(32)   NOT NULL,
    idempotency_key   varchar(64),
    requested_at      timestamptz   NOT NULL DEFAULT now(),
    processed_by      bigint        REFERENCES user_account (id) ON DELETE SET NULL,
    processed_at      timestamptz,

    CONSTRAINT uq_refund_idempotency UNIQUE (idempotency_key),
    CONSTRAINT ck_refund_status CHECK (status IN
        ('PENDING', 'APPROVED', 'PROCESSED', 'REJECTED')),
    CONSTRAINT ck_refund_method CHECK (method IN
        ('BANK_TRANSFER', 'MOCK_GATEWAY', 'WALLET', 'CASH_ON_DELIVERY')),
    CONSTRAINT ck_refund_amount CHECK (amount > 0)
);

COMMENT ON TABLE refund IS
    'Partial refunds are supported: one seller_order, or a subset of its items [RQ-11].';

CREATE INDEX ix_refund_order ON refund (sales_order_id);
CREATE INDEX ix_refund_status ON refund (status, requested_at);


-- Closes V6's forward references now that both targets exist.
ALTER TABLE seller_order
    ADD CONSTRAINT fk_seller_order_commission_rule
        FOREIGN KEY (commission_rule_id) REFERENCES commission_rule (id) ON DELETE SET NULL,
    ADD CONSTRAINT fk_seller_order_payout
        FOREIGN KEY (payout_id) REFERENCES payout (id) ON DELETE SET NULL;
