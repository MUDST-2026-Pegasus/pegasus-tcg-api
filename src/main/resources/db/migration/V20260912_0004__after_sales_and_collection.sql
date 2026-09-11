-- Schema sprint 6/7 — returns, reviews, and the cards a person keeps.
--
-- Closes refund's forward reference to return_request, left open in V7.

CREATE TABLE return_request (
    id                     bigint        PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    seller_order_id        bigint        NOT NULL REFERENCES seller_order (id) ON DELETE RESTRICT,
    buyer_id               bigint        NOT NULL REFERENCES user_account (id) ON DELETE RESTRICT,
    reason_code            varchar(32)   NOT NULL,
    description            text,
    status                 varchar(32)   NOT NULL DEFAULT 'REQUESTED',
    resolution             varchar(32),
    refund_amount          decimal(14,2),
    evidence_keys          jsonb         NOT NULL DEFAULT '[]',
    return_tracking_number varchar(64),
    requested_at           timestamptz   NOT NULL DEFAULT now(),
    reviewed_by            bigint        REFERENCES user_account (id) ON DELETE SET NULL,
    reviewed_at            timestamptz,
    received_at            timestamptz,
    closed_at              timestamptz,

    CONSTRAINT ck_return_reason CHECK (reason_code IN
        ('NOT_AS_DESCRIBED', 'DAMAGED', 'WRONG_ITEM', 'NOT_RECEIVED', 'COUNTERFEIT', 'OTHER')),
    CONSTRAINT ck_return_status CHECK (status IN
        ('REQUESTED', 'APPROVED', 'REJECTED', 'AWAITING_RETURN_SHIPMENT',
         'IN_TRANSIT', 'RECEIVED', 'REFUNDED', 'CLOSED')),
    CONSTRAINT ck_return_resolution CHECK (resolution IS NULL OR resolution IN
        ('FULL_REFUND', 'PARTIAL_REFUND', 'REPLACEMENT', 'REJECTED')),
    CONSTRAINT ck_return_refund_amount CHECK (refund_amount IS NULL OR refund_amount > 0),
    CONSTRAINT ck_return_evidence CHECK (jsonb_typeof(evidence_keys) = 'array')
);

COMMENT ON TABLE return_request IS
    'Opening a request must stop the sub-order''s auto_complete immediately, or escrow releases mid-dispute.';
COMMENT ON COLUMN return_request.evidence_keys IS
    'Array of object-storage keys for the buyer''s photos or video.';

CREATE INDEX ix_return_request_order ON return_request (seller_order_id);
CREATE INDEX ix_return_request_status ON return_request (status, requested_at);
CREATE INDEX ix_return_request_buyer ON return_request (buyer_id);


CREATE TABLE return_item (
    id                bigint  PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    return_request_id bigint  NOT NULL REFERENCES return_request (id) ON DELETE CASCADE,
    order_item_id     bigint  NOT NULL REFERENCES order_item (id) ON DELETE RESTRICT,
    quantity          int     NOT NULL,
    restock           boolean NOT NULL DEFAULT true,

    CONSTRAINT ck_return_item_quantity CHECK (quantity > 0)
);

COMMENT ON COLUMN return_item.restock IS
    'true puts the card back into stock: a RETURN_RESTOCK movement, and the unit goes to RETURNED.';

CREATE UNIQUE INDEX ux_return_item ON return_item (return_request_id, order_item_id);


CREATE TABLE seller_review (
    id                bigint       PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    seller_order_id   bigint       NOT NULL REFERENCES seller_order (id) ON DELETE CASCADE,
    seller_profile_id bigint       NOT NULL REFERENCES seller_profile (id) ON DELETE CASCADE,
    reviewer_id       bigint       NOT NULL REFERENCES user_account (id) ON DELETE RESTRICT,
    rating            smallint     NOT NULL,
    title             varchar(150),
    comment           text,
    reply_comment     text,
    replied_at        timestamptz,
    is_visible        boolean      NOT NULL DEFAULT true,
    created_at        timestamptz  NOT NULL DEFAULT now(),
    updated_at        timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT uq_seller_review_order UNIQUE (seller_order_id),
    CONSTRAINT ck_seller_review_rating CHECK (rating BETWEEN 1 AND 5)
);

COMMENT ON COLUMN seller_review.is_visible IS 'Admins can hide a review that breaks the rules.';

CREATE INDEX ix_seller_review_seller
    ON seller_review (seller_profile_id, is_visible, created_at);
CREATE INDEX ix_seller_review_reviewer ON seller_review (reviewer_id);

CREATE TRIGGER trg_seller_review_updated_at
    BEFORE UPDATE ON seller_review
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();


-- Only a finished order can be reviewed [RQ-15]. The unique key above already
-- stops a second review; this stops an early one.
CREATE FUNCTION seller_review_requires_completed_order() RETURNS trigger
    LANGUAGE plpgsql AS $$
DECLARE
    order_status varchar(32);
BEGIN
    SELECT status INTO order_status FROM seller_order WHERE id = NEW.seller_order_id;

    IF order_status IS DISTINCT FROM 'COMPLETED' THEN
        RAISE EXCEPTION 'seller_order % is %, so it cannot be reviewed yet',
            NEW.seller_order_id, coalesce(order_status, 'missing')
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_seller_review_completed_only
    BEFORE INSERT ON seller_review
    FOR EACH ROW EXECUTE FUNCTION seller_review_requires_completed_order();


-- Denormalised so a profile page can show stars without aggregating every time.
-- Kept in step through the outbox, not synchronously.
CREATE TABLE seller_rating_summary (
    seller_profile_id bigint       PRIMARY KEY REFERENCES seller_profile (id) ON DELETE CASCADE,
    rating_count      int          NOT NULL DEFAULT 0,
    rating_sum        int          NOT NULL DEFAULT 0,
    rating_avg        decimal(3,2) NOT NULL DEFAULT 0,
    count_1           int          NOT NULL DEFAULT 0,
    count_2           int          NOT NULL DEFAULT 0,
    count_3           int          NOT NULL DEFAULT 0,
    count_4           int          NOT NULL DEFAULT 0,
    count_5           int          NOT NULL DEFAULT 0,
    updated_at        timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT ck_rating_summary_avg CHECK (rating_avg BETWEEN 0 AND 5),
    CONSTRAINT ck_rating_summary_counts CHECK (
        rating_count = count_1 + count_2 + count_3 + count_4 + count_5)
);

CREATE TRIGGER trg_seller_rating_summary_updated_at
    BEFORE UPDATE ON seller_rating_summary
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();


-- A person's own cards. Deliberately not listing_unit: those are stock, with a
-- cost, a reservation state and a place in the books. These are keepsakes, and
-- mixing them would force every seller-side query to filter them back out.
CREATE TABLE collection_item (
    id                     bigint        PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    user_id                bigint        NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    catalog_variant_id     bigint        NOT NULL REFERENCES catalog_variant (id) ON DELETE RESTRICT,
    condition_code         varchar(16)   NOT NULL DEFAULT 'NM',
    quantity               int           NOT NULL DEFAULT 1,
    source                 varchar(32)   NOT NULL DEFAULT 'MANUAL',
    source_order_item_id   bigint        REFERENCES order_item (id) ON DELETE SET NULL,
    source_listing_unit_id bigint        REFERENCES listing_unit (id) ON DELETE SET NULL,
    grading_company        varchar(20),
    grade_value            decimal(3,1),
    cert_number            varchar(64),
    acquired_price         decimal(14,2),
    acquired_at            timestamptz,
    image_key              varchar(500),
    personal_note          varchar(500),
    is_public              boolean       NOT NULL DEFAULT false,
    created_at             timestamptz   NOT NULL DEFAULT now(),
    updated_at             timestamptz   NOT NULL DEFAULT now(),
    deleted_at             timestamptz,

    CONSTRAINT ck_collection_item_condition CHECK (condition_code IN
        ('NM', 'LP', 'MP', 'HP', 'DMG', 'SEALED')),
    CONSTRAINT ck_collection_item_source CHECK (source IN ('MANUAL', 'PURCHASE')),
    CONSTRAINT ck_collection_item_quantity CHECK (quantity > 0),
    -- Bought cards arrive one at a time and always know where they came from.
    CONSTRAINT ck_collection_item_purchase CHECK (
        source <> 'PURCHASE' OR (source_order_item_id IS NOT NULL AND quantity = 1)),
    -- Hand-added cards have no order behind them.
    CONSTRAINT ck_collection_item_manual CHECK (
        source <> 'MANUAL'
        OR (source_order_item_id IS NULL AND source_listing_unit_id IS NULL))
);

COMMENT ON COLUMN collection_item.catalog_variant_id IS
    'The FK is the rule that a card must exist in the shared catalogue [RQ-3]; no free-text card names.';
COMMENT ON COLUMN collection_item.is_public IS
    'Per card, because people show off one card and hide the expensive one.';
COMMENT ON COLUMN collection_item.deleted_at IS
    'Soft delete on purpose: see ux_collection_item_source below.';

-- Granting a card is meant to be idempotent whichever way it is triggered. Today
-- it is a direct call at COMPLETED; once the outbox lands it becomes an
-- at-least-once event, and the same seller_order.completed will arrive twice.
-- This index is what makes the second arrival a no-op either way.
--
-- It only works with soft delete: a card the owner threw away leaves its row
-- behind, so a retrying worker collides here and skips instead of resurrecting
-- something the owner already removed.
CREATE UNIQUE INDEX ux_collection_item_source
    ON collection_item (source_order_item_id, source_listing_unit_id)
    WHERE source_listing_unit_id IS NOT NULL;

CREATE INDEX ix_collection_item_owner
    ON collection_item (user_id, catalog_variant_id, condition_code);
CREATE INDEX ix_collection_item_public ON collection_item (user_id, is_public, created_at);
CREATE INDEX ix_collection_item_variant ON collection_item (catalog_variant_id);
CREATE INDEX ix_collection_item_source_unit ON collection_item (source_listing_unit_id);

CREATE TRIGGER trg_collection_item_updated_at
    BEFORE UPDATE ON collection_item
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();


CREATE TABLE wishlist_item (
    id                 bigint        PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    user_id            bigint        NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    catalog_variant_id bigint        NOT NULL REFERENCES catalog_variant (id) ON DELETE CASCADE,
    target_price       decimal(14,2),
    created_at         timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT ck_wishlist_target_price CHECK (target_price IS NULL OR target_price > 0)
);

COMMENT ON COLUMN wishlist_item.target_price IS
    'Notify the watcher once the market price drops below this.';

CREATE UNIQUE INDEX ux_wishlist_item ON wishlist_item (user_id, catalog_variant_id);


-- Closes V7's forward reference now that return_request exists.
ALTER TABLE refund
    ADD CONSTRAINT fk_refund_return_request
        FOREIGN KEY (return_request_id) REFERENCES return_request (id) ON DELETE SET NULL;
