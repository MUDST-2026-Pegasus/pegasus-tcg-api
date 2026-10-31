-- Auctions [TCG-614, contract in TCG-613]. A seller puts one physical card from
-- stock up for auction; buyers bid it up; the highest bidder when time runs out
-- pays through the ordinary checkout within 24 hours. A winner who does not pay
-- gets a strike (three bar bidding for 30 days) and the seller either offers the
-- card to the runner-up at their bid or puts it back in stock.
--
-- Status codes match model/AuctionStatus, BidStatus and AuctionOfferStatus.

-- auction.auction_number is AUC-YYYY-NNNN, made in AuctionRepository from this
-- counter so two sellers opening at once never share a number. Never reset.
CREATE SEQUENCE auction_number_seq AS bigint START WITH 1 INCREMENT BY 1 NO CYCLE;

COMMENT ON SEQUENCE auction_number_seq IS
    'Counter behind auction.auction_number. A rolled-back insert burns a number rather than reusing it.';


CREATE TABLE auction (
    id                      bigint        PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    auction_number          varchar(20)   NOT NULL,
    seller_profile_id       bigint        NOT NULL REFERENCES seller_profile (id) ON DELETE RESTRICT,
    listing_unit_id         bigint        NOT NULL REFERENCES listing_unit (id) ON DELETE RESTRICT,
    catalog_variant_id      bigint        NOT NULL REFERENCES catalog_variant (id) ON DELETE RESTRICT,
    condition_code          varchar(16)   NOT NULL,

    starting_price          decimal(14,2) NOT NULL,
    bid_increment           decimal(14,2) NOT NULL,
    buy_now_price           decimal(14,2),
    current_price           decimal(14,2) NOT NULL,
    leading_bid_id          bigint,
    bid_count               int           NOT NULL DEFAULT 0,
    bidder_count            int           NOT NULL DEFAULT 0,

    started_at              timestamptz   NOT NULL DEFAULT now(),
    original_ends_at        timestamptz   NOT NULL,
    ends_at                 timestamptz   NOT NULL,
    extension_count         int           NOT NULL DEFAULT 0,
    first_bid_at            timestamptz,

    seller_note             varchar(500),
    seller_note_updated_at  timestamptz,

    winner_id               bigint        REFERENCES user_account (id) ON DELETE RESTRICT,
    winning_amount          decimal(14,2),
    closed_at               timestamptz,
    payment_due_at          timestamptz,
    decision_due_at         timestamptz,
    sales_order_id          bigint        REFERENCES sales_order (id) ON DELETE SET NULL,

    status                  varchar(32)   NOT NULL DEFAULT 'LIVE',
    cancelled_at            timestamptz,
    suspended_reason        varchar(500),
    suspended_by            bigint        REFERENCES user_account (id) ON DELETE SET NULL,
    suspended_at            timestamptz,
    relisted_from_id        bigint        REFERENCES auction (id) ON DELETE SET NULL,

    version                 int           NOT NULL DEFAULT 0,
    created_at              timestamptz   NOT NULL DEFAULT now(),
    updated_at              timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT uq_auction_number UNIQUE (auction_number),
    CONSTRAINT ck_auction_status CHECK (status IN
        ('LIVE', 'AWAITING_PAYMENT', 'AWAITING_SELLER_DECISION', 'OFFERED_TO_SECOND',
         'SOLD', 'UNSOLD', 'CANCELLED', 'SUSPENDED')),
    CONSTRAINT ck_auction_condition CHECK (condition_code IN
        ('NM', 'LP', 'MP', 'HP', 'DMG', 'SEALED')),
    CONSTRAINT ck_auction_prices CHECK (
        starting_price > 0 AND bid_increment > 0 AND current_price >= starting_price),
    -- eBay-style buy-now: only above the starting price; null means the seller set none.
    CONSTRAINT ck_auction_buy_now CHECK (buy_now_price IS NULL OR buy_now_price > starting_price),
    CONSTRAINT ck_auction_counts CHECK (
        bid_count >= 0 AND bidder_count >= 0 AND bidder_count <= bid_count AND extension_count >= 0),
    CONSTRAINT ck_auction_leading_bid CHECK ((bid_count = 0) = (leading_bid_id IS NULL)),
    CONSTRAINT ck_auction_period CHECK (
        original_ends_at > started_at AND ends_at >= original_ends_at),
    CONSTRAINT ck_auction_winner CHECK (
        status NOT IN ('AWAITING_PAYMENT', 'SOLD')
        OR (winner_id IS NOT NULL AND winning_amount IS NOT NULL)),
    CONSTRAINT ck_auction_payment_due CHECK (
        status <> 'AWAITING_PAYMENT' OR payment_due_at IS NOT NULL),
    CONSTRAINT ck_auction_decision_due CHECK (
        status <> 'AWAITING_SELLER_DECISION' OR decision_due_at IS NOT NULL),
    CONSTRAINT ck_auction_suspended CHECK (
        status <> 'SUSPENDED' OR (suspended_reason IS NOT NULL AND suspended_at IS NOT NULL)),
    CONSTRAINT ck_auction_cancelled CHECK (
        status <> 'CANCELLED' OR (cancelled_at IS NOT NULL AND bid_count = 0))
);

COMMENT ON TABLE auction IS
    'One card from a seller''s stock up for auction. The card is ON_AUCTION in listing_unit while the auction is open.';
COMMENT ON COLUMN auction.current_price IS
    'The leading bid, or starting_price while there are no bids.';
COMMENT ON COLUMN auction.original_ends_at IS
    'The closing time the seller picked; ends_at moves past it when a bid in the last 2 minutes extends the auction.';
COMMENT ON COLUMN auction.winner_id IS
    'Whoever must pay now: the top bidder, a buy-now buyer, or the runner-up who accepted an offer.';
COMMENT ON COLUMN auction.sales_order_id IS
    'The order placed by the auction checkout. Null until the winner checks out; cleared if that order is cancelled.';
COMMENT ON COLUMN auction.relisted_from_id IS
    'The auction this one was reopened from after a winner did not pay.';
COMMENT ON COLUMN auction.version IS
    'Optimistic lock, bumped on every change alongside the row lock taken when bidding.';

-- A card can sit in only one open auction at a time.
CREATE UNIQUE INDEX uq_auction_open_unit ON auction (listing_unit_id)
    WHERE status IN ('LIVE', 'AWAITING_PAYMENT', 'AWAITING_SELLER_DECISION', 'OFFERED_TO_SECOND');
-- The scheduler sweeps: closing live auctions, unpaid wins, and seller decisions that ran out.
CREATE INDEX ix_auction_status_ends ON auction (status, ends_at);
CREATE INDEX ix_auction_status_payment_due ON auction (status, payment_due_at);
CREATE INDEX ix_auction_status_decision_due ON auction (status, decision_due_at);
CREATE INDEX ix_auction_seller ON auction (seller_profile_id, status, created_at);
CREATE INDEX ix_auction_winner ON auction (winner_id, status) WHERE winner_id IS NOT NULL;

CREATE TRIGGER trg_auction_updated_at
    BEFORE UPDATE ON auction
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();


-- Bids cannot be withdrawn. Only an admin suspending the auction cancels them.
CREATE TABLE auction_bid (
    id              bigint        PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    auction_id      bigint        NOT NULL REFERENCES auction (id) ON DELETE RESTRICT,
    bidder_id       bigint        NOT NULL REFERENCES user_account (id) ON DELETE RESTRICT,
    amount          decimal(14,2) NOT NULL,
    status          varchar(16)   NOT NULL DEFAULT 'ACTIVE',
    idempotency_key varchar(64),
    created_at      timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT ck_auction_bid_amount CHECK (amount > 0),
    CONSTRAINT ck_auction_bid_status CHECK (status IN ('ACTIVE', 'CANCELLED')),
    CONSTRAINT uq_auction_bid_idempotency UNIQUE (auction_id, idempotency_key)
);

COMMENT ON COLUMN auction_bid.idempotency_key IS
    'Stops a double-tapped bid from counting twice.';

CREATE INDEX ix_auction_bid_amount ON auction_bid (auction_id, amount);
CREATE INDEX ix_auction_bid_bidder ON auction_bid (bidder_id, created_at);

ALTER TABLE auction
    ADD CONSTRAINT fk_auction_leading_bid
        FOREIGN KEY (leading_bid_id) REFERENCES auction_bid (id) ON DELETE RESTRICT;


-- The offer a seller sends the runner-up, at their own bid, after the winner did not pay.
CREATE TABLE auction_offer (
    id           bigint        PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    auction_id   bigint        NOT NULL REFERENCES auction (id) ON DELETE RESTRICT,
    recipient_id bigint        NOT NULL REFERENCES user_account (id) ON DELETE RESTRICT,
    bid_id       bigint        NOT NULL REFERENCES auction_bid (id) ON DELETE RESTRICT,
    amount       decimal(14,2) NOT NULL,
    status       varchar(16)   NOT NULL DEFAULT 'PENDING',
    expires_at   timestamptz   NOT NULL,
    responded_at timestamptz,
    created_at   timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT ck_auction_offer_amount CHECK (amount > 0),
    CONSTRAINT ck_auction_offer_status CHECK (status IN ('PENDING', 'ACCEPTED', 'DECLINED', 'EXPIRED')),
    CONSTRAINT ck_auction_offer_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_auction_offer_responded CHECK (
        status NOT IN ('ACCEPTED', 'DECLINED') OR responded_at IS NOT NULL)
);

-- One open offer per auction.
CREATE UNIQUE INDEX uq_auction_offer_pending ON auction_offer (auction_id) WHERE status = 'PENDING';
CREATE INDEX ix_auction_offer_expiry ON auction_offer (status, expires_at);
CREATE INDEX ix_auction_offer_recipient ON auction_offer (recipient_id, status);


-- One unpaid win. Three that still count bar the user from bidding for 30 days
-- (bidding_ban); an admin can clear a strike that was not the user's fault.
CREATE TABLE bidder_strike (
    id             bigint        PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    user_id        bigint        NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    auction_id     bigint        NOT NULL REFERENCES auction (id) ON DELETE RESTRICT,
    sales_order_id bigint        REFERENCES sales_order (id) ON DELETE SET NULL,
    struck_at      timestamptz   NOT NULL DEFAULT now(),
    cleared_at     timestamptz,
    cleared_by     bigint        REFERENCES user_account (id) ON DELETE SET NULL,
    clear_reason   varchar(500),

    CONSTRAINT uq_bidder_strike_user_auction UNIQUE (user_id, auction_id)
);

COMMENT ON COLUMN bidder_strike.cleared_at IS
    'Set when an admin lifts the ban or the ban is served; a cleared strike no longer counts.';

CREATE INDEX ix_bidder_strike_user ON bidder_strike (user_id, struck_at) WHERE cleared_at IS NULL;


-- A bar on bidding. Buying at a fixed price is still allowed.
CREATE TABLE bidding_ban (
    id           bigint        PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    user_id      bigint        NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    banned_from  timestamptz   NOT NULL DEFAULT now(),
    banned_until timestamptz   NOT NULL,
    lifted_at    timestamptz,
    lifted_by    bigint        REFERENCES user_account (id) ON DELETE SET NULL,
    lift_reason  varchar(500),
    created_at   timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT ck_bidding_ban_period CHECK (banned_until > banned_from),
    CONSTRAINT ck_bidding_ban_lifted CHECK (lifted_at IS NULL OR lifted_by IS NOT NULL)
);

CREATE INDEX ix_bidding_ban_user ON bidding_ban (user_id, banned_until);


-- A card in an open auction is in hand but on no listing, and is not for sale in
-- the shop. The listing quantity trigger counts only LISTED and RESERVED, so it
-- already leaves these out.
ALTER TABLE listing_unit DROP CONSTRAINT ck_listing_unit_status;
ALTER TABLE listing_unit ADD CONSTRAINT ck_listing_unit_status CHECK (status IN
    ('IN_STOCK', 'LISTED', 'RESERVED', 'ON_AUCTION', 'SOLD', 'RETURNED', 'WRITTEN_OFF'));
ALTER TABLE listing_unit ADD CONSTRAINT ck_listing_unit_on_auction_unlisted CHECK (
    status <> 'ON_AUCTION' OR listing_id IS NULL);


-- Where an order came from. An auction order has exactly one card at the locked price.
ALTER TABLE sales_order
    ADD COLUMN source     varchar(16) NOT NULL DEFAULT 'CART',
    ADD COLUMN auction_id bigint      REFERENCES auction (id) ON DELETE RESTRICT;
ALTER TABLE sales_order ADD CONSTRAINT ck_sales_order_source CHECK (source IN ('CART', 'AUCTION'));
ALTER TABLE sales_order ADD CONSTRAINT ck_sales_order_auction CHECK (
    (source = 'AUCTION') = (auction_id IS NOT NULL));

COMMENT ON COLUMN sales_order.source IS
    'CART for the ordinary checkout, AUCTION for an auction win. The payment-timeout sweep skips AUCTION orders; the auction has its own 24-hour deadline.';

CREATE INDEX ix_sales_order_auction ON sales_order (auction_id) WHERE auction_id IS NOT NULL;
