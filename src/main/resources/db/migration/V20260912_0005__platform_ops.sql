-- Schema sprint 7/7 — config an admin can change without a deploy.
--
-- DEFERRED, not dropped: notification, outbox_event and audit_log were cut from
-- this pass. They are cross-cutting rather than part of any one feature, and
-- nothing in the golden path needs them to exist yet. When they come back they
-- belong in the lead's own migration range (V10-V19), as their own file.
--
-- Two places already assume they are coming, and neither breaks in the meantime:
--   * collection_item.ux_collection_item_source is what makes at-least-once
--     event delivery safe. Without the outbox, the grant is a direct call and
--     the index simply never fires.
--   * seller_rating_summary is meant to be refreshed asynchronously. Until then
--     recompute it in the same transaction as the review.

CREATE TABLE platform_setting (
    setting_key   varchar(100) PRIMARY KEY,
    setting_value jsonb        NOT NULL,
    description   varchar(255),
    updated_by    bigint       REFERENCES user_account (id) ON DELETE SET NULL,
    updated_at    timestamptz  NOT NULL DEFAULT now()
);

COMMENT ON TABLE platform_setting IS
    'Values an admin edits at runtime [RQ-14], e.g. escrow.auto_release_days, order.cancel_window_hours.';

CREATE TRIGGER trg_platform_setting_updated_at
    BEFORE UPDATE ON platform_setting
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();


-- Seed the settings the order and escrow flows read at runtime, so neither has
-- to carry a hard-coded fallback.
INSERT INTO platform_setting (setting_key, setting_value, description) VALUES
    ('escrow.auto_release_days',   '7',       'Days after delivery before escrow releases without buyer confirmation.'),
    ('order.cancel_window_hours',  '24',      'How long a buyer may cancel an unshipped order.'),
    ('return.request_window_days', '7',       'Days after delivery a return may still be opened.'),
    ('commission.default_rate',    '5.0',     'Percent used when no commission_rule matches.'),
    ('payout.minimum_amount',      '100.00',  'Smallest payout a seller may request.'),
    ('pricing.median_min_sample',  '3',       'Below this sample size the median is ignored for AUTO_MEDIAN.');
