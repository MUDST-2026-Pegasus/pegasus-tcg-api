-- Exactly one payout account per seller.
--
-- The previous change tied every account to an approved verification but still
-- let several pile up, one per approval — which left "choose the default one"
-- and "delete this one" as real operations, and left room for a seller to be
-- paid into an account that is not the one currently verified.
--
-- Now the account is simply the verified account. Re-verifying to change bank
-- replaces the row rather than adding another, so there is never a choice to
-- make and never a stale account to pick by mistake.
--
-- What goes away with it:
--   * is_default, meaningless once there is only ever one row
--   * PUT  /sellers/me/payout-accounts/{id}/default
--   * DELETE /sellers/me/payout-accounts/{id}
--
-- The remaining read moves to the singular /sellers/me/payout-account.

-- Keep the newest account per seller; older ones are superseded by definition.
DELETE FROM seller_payout_account a
      USING seller_payout_account newer
      WHERE a.seller_profile_id = newer.seller_profile_id
        AND a.id < newer.id;

DROP INDEX IF EXISTS ux_payout_account_default;
DROP INDEX IF EXISTS ux_payout_account_verification;
DROP INDEX IF EXISTS ix_payout_account_seller;

ALTER TABLE seller_payout_account
    DROP COLUMN is_default;

-- The invariant, stated once: a seller has one account, and it names the
-- verification that approved it.
ALTER TABLE seller_payout_account
    ADD CONSTRAINT uq_payout_account_seller UNIQUE (seller_profile_id);

COMMENT ON TABLE seller_payout_account IS
    'One row per seller, always the account from their current approved verification.';
