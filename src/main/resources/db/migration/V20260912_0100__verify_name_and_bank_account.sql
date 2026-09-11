-- Seller verification no longer collects identity documents.
--
-- Checking a national ID means holding data that must be encrypted, access-
-- controlled and eventually purged, and a card marketplace does not need that to
-- decide whether someone may sell. What it actually needs is a name to put on a
-- dispute and an account to send money to, so that is all this now asks for.
--
-- Consequences elsewhere:
--   * PiiCryptoService and pegasus.pii config are gone; nothing is hashed,
--     masked or encrypted any more.
--   * Bank account numbers are stored as written. They are still not public:
--     only the owning seller and an admin can read them back.
--
-- The new columns arrive with a blank default so existing rows survive the
-- change; the default is then dropped so new rows must supply a real value. Any
-- row written under the old shape keeps blanks and should be resubmitted.

-- Dropping document_number_hash takes ix_seller_verification_doc_hash with it.
ALTER TABLE seller_verification
    DROP COLUMN document_type,
    DROP COLUMN document_number_masked,
    DROP COLUMN document_number_hash,
    DROP COLUMN document_image_key,
    DROP COLUMN selfie_image_key;

ALTER TABLE seller_verification
    ADD COLUMN legal_first_name    varchar(100) NOT NULL DEFAULT '',
    ADD COLUMN legal_last_name     varchar(100) NOT NULL DEFAULT '',
    ADD COLUMN bank_code           varchar(10)  NOT NULL DEFAULT '',
    ADD COLUMN bank_name           varchar(100) NOT NULL DEFAULT '',
    ADD COLUMN bank_account_number varchar(34)  NOT NULL DEFAULT '';

ALTER TABLE seller_verification
    ALTER COLUMN legal_first_name    DROP DEFAULT,
    ALTER COLUMN legal_last_name     DROP DEFAULT,
    ALTER COLUMN bank_code           DROP DEFAULT,
    ALTER COLUMN bank_name           DROP DEFAULT,
    ALTER COLUMN bank_account_number DROP DEFAULT;

COMMENT ON COLUMN seller_verification.legal_first_name IS
    'Name on the bank account, which is the only identity claim being checked.';
COMMENT ON COLUMN seller_verification.bank_account_number IS
    'Stored as written. Readable by the owning seller and by an admin, nobody else.';

-- One bank account belongs to one seller. Without this, the same account could
-- back several seller profiles and a payout dispute would have no single owner.
CREATE UNIQUE INDEX ux_seller_verification_bank_account
    ON seller_verification (bank_account_number)
    WHERE status = 'APPROVED' AND bank_account_number <> '';


-- Payout accounts hold the number in the clear too, for the same reason.
ALTER TABLE seller_payout_account
    DROP COLUMN account_number_encrypted,
    DROP COLUMN account_number_masked;

ALTER TABLE seller_payout_account
    ADD COLUMN account_number varchar(34) NOT NULL DEFAULT '';

ALTER TABLE seller_payout_account
    ALTER COLUMN account_number DROP DEFAULT;

COMMENT ON COLUMN seller_payout_account.account_number IS
    'Stored as written; approval copies it across from the verification.';
