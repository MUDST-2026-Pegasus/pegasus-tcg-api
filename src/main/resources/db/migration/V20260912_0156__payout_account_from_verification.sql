-- A payout account may only be an account that was actually verified.
--
-- Until now a VERIFIED seller could add any bank account they liked through
-- POST /sellers/me/payout-accounts, which made checking the account at signup
-- pointless: verify account X, get paid into account Y. The endpoint is gone,
-- and this column is what makes the rule structural rather than a matter of
-- which routes happen to exist — every payout account now has to name the
-- approved verification it came from.
--
-- Changing bank therefore means submitting a new verification and an admin
-- approving it, which is the same check the first account went through.

ALTER TABLE seller_payout_account
    ADD COLUMN verification_id bigint REFERENCES seller_verification (id) ON DELETE RESTRICT;

-- Existing accounts were created by approval, so they match on account number.
UPDATE seller_payout_account a
   SET verification_id = v.id
  FROM seller_verification v
 WHERE v.seller_profile_id = a.seller_profile_id
   AND v.bank_account_number = a.account_number
   AND v.status = 'APPROVED';

-- Anything still unmatched is an account nobody ever verified — precisely what
-- this change forbids, so it does not survive it.
DELETE FROM seller_payout_account WHERE verification_id IS NULL;

ALTER TABLE seller_payout_account
    ALTER COLUMN verification_id SET NOT NULL;

COMMENT ON COLUMN seller_payout_account.verification_id IS
    'The approved verification that produced this account. No other way to create one.';

-- One account per verification: re-approving the same request must not add a
-- second copy, however many times it is retried.
CREATE UNIQUE INDEX ux_payout_account_verification
    ON seller_payout_account (verification_id);
