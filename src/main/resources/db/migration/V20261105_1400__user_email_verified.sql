-- Add email_verified_at to user_account [TCG-616, ISSUE-005]
-- Used to verify email address before bidding or buying now on auctions.

ALTER TABLE user_account ADD COLUMN email_verified_at timestamptz;

-- Existing accounts created before email verification are marked verified
UPDATE user_account SET email_verified_at = created_at WHERE email_verified_at IS NULL;
