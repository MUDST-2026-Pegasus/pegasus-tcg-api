ALTER TABLE seller_verification
    ADD COLUMN bank_book_image_key VARCHAR(500) NOT NULL DEFAULT '';

COMMENT ON COLUMN seller_verification.bank_book_image_key IS 'MinIO object key for the bank book image, not a public URL';

ALTER TABLE seller_verification
    ALTER COLUMN bank_book_image_key DROP DEFAULT;
