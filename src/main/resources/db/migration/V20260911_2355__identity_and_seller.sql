

CREATE TABLE address (
    id                  bigint       PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    user_id             bigint       NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    label               varchar(50),
    recipient_name      varchar(150) NOT NULL,
    phone               varchar(20)  NOT NULL,
    line1               varchar(255) NOT NULL,
    line2               varchar(255),
    subdistrict         varchar(100),
    district            varchar(100),
    province            varchar(100) NOT NULL,
    postal_code         varchar(10)  NOT NULL,
    country_code        char(2)      NOT NULL DEFAULT 'TH',
    is_default_shipping boolean      NOT NULL DEFAULT false,
    is_default_billing  boolean      NOT NULL DEFAULT false,
    created_at          timestamptz  NOT NULL DEFAULT now(),
    updated_at          timestamptz  NOT NULL DEFAULT now(),
    deleted_at          timestamptz
);

COMMENT ON TABLE address IS
    'Never hard-delete: orders point here. An order also keeps its own address snapshot.';

CREATE INDEX ix_address_user ON address (user_id, deleted_at);

CREATE TRIGGER trg_address_updated_at
    BEFORE UPDATE ON address
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();



CREATE TABLE seller_profile (
    id                 bigint      PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    user_id            bigint      NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    status             varchar(32) NOT NULL DEFAULT 'NOT_APPLIED',
    verified_at        timestamptz,
    suspended_reason   varchar(255),
    handling_days      smallint    NOT NULL DEFAULT 2,
    vacation_mode      boolean     NOT NULL DEFAULT false,
    auto_accept_orders boolean     NOT NULL DEFAULT true,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT uq_seller_profile_user UNIQUE (user_id),
    CONSTRAINT ck_seller_profile_status CHECK (status IN
        ('NOT_APPLIED', 'PENDING', 'VERIFIED', 'REJECTED', 'SUSPENDED')),
    CONSTRAINT ck_seller_profile_handling_days CHECK (handling_days >= 0)
);

COMMENT ON COLUMN seller_profile.status IS
    'Only VERIFIED may publish a listing; enforced in the service layer.';
COMMENT ON COLUMN seller_profile.vacation_mode IS
    'Hides every listing of this seller at once, without touching listing.status.';

CREATE INDEX ix_seller_profile_status ON seller_profile (status);

CREATE TRIGGER trg_seller_profile_updated_at
    BEFORE UPDATE ON seller_profile
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();


-- PII. The real document number is never stored: a masked form for humans and a
-- salted hash so the same card cannot be used to open a second seller account.
CREATE TABLE seller_verification (
    id                     bigint       PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    seller_profile_id      bigint       NOT NULL REFERENCES seller_profile (id) ON DELETE CASCADE,
    document_type          varchar(32)  NOT NULL,
    document_number_masked varchar(32)  NOT NULL,
    document_number_hash   varchar(128) NOT NULL,
    document_image_key     varchar(500) NOT NULL,
    selfie_image_key       varchar(500),
    status                 varchar(32)  NOT NULL DEFAULT 'SUBMITTED',
    submitted_at           timestamptz  NOT NULL DEFAULT now(),
    reviewed_by            bigint       REFERENCES user_account (id) ON DELETE SET NULL,
    reviewed_at            timestamptz,
    rejection_reason       varchar(255),

    CONSTRAINT ck_seller_verification_doc_type CHECK (document_type IN
        ('NATIONAL_ID', 'PASSPORT', 'COMPANY_REGISTRATION')),
    CONSTRAINT ck_seller_verification_status CHECK (status IN
        ('SUBMITTED', 'UNDER_REVIEW', 'APPROVED', 'REJECTED'))
);

COMMENT ON COLUMN seller_verification.document_number_masked IS
    'Display form only, e.g. x-xxxx-xxxxx-12-3. The real number is never stored.';
COMMENT ON COLUMN seller_verification.document_image_key IS
    'Object key in S3/MinIO, not a public URL.';

CREATE INDEX ix_seller_verification_profile ON seller_verification (seller_profile_id, status);
CREATE INDEX ix_seller_verification_doc_hash ON seller_verification (document_number_hash);


-- Sellers ship on their own and price shipping themselves [RQ-12].
CREATE TABLE seller_shipping_option (
    id                bigint        PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    seller_profile_id bigint        NOT NULL REFERENCES seller_profile (id) ON DELETE CASCADE,
    name              varchar(100)  NOT NULL,
    carrier_code      varchar(32),
    base_fee          decimal(14,2) NOT NULL DEFAULT 0,
    per_item_fee      decimal(14,2) NOT NULL DEFAULT 0,
    free_threshold    decimal(14,2),
    est_days_min      smallint,
    est_days_max      smallint,
    is_active         boolean       NOT NULL DEFAULT true,
    display_order     smallint      NOT NULL DEFAULT 0,

    CONSTRAINT ck_shipping_option_fees CHECK (base_fee >= 0 AND per_item_fee >= 0),
    CONSTRAINT ck_shipping_option_est_days CHECK (
        est_days_min IS NULL OR est_days_max IS NULL OR est_days_min <= est_days_max)
);

CREATE INDEX ix_shipping_option_seller ON seller_shipping_option (seller_profile_id, is_active);


-- PII. Only the masked number is readable; the full number is encrypted at the
-- column level and decrypted nowhere except the payout run.
CREATE TABLE seller_payout_account (
    id                       bigint       PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    seller_profile_id        bigint       NOT NULL REFERENCES seller_profile (id) ON DELETE CASCADE,
    bank_code                varchar(10)  NOT NULL,
    bank_name                varchar(100) NOT NULL,
    account_name             varchar(150) NOT NULL,
    account_number_masked    varchar(32)  NOT NULL,
    account_number_encrypted bytea        NOT NULL,
    is_default               boolean      NOT NULL DEFAULT false,
    verified_at              timestamptz,
    created_at               timestamptz  NOT NULL DEFAULT now()
);

CREATE INDEX ix_payout_account_seller ON seller_payout_account (seller_profile_id, is_default);

-- One default account per seller, enforced where it matters rather than by a
-- flag the service has to keep tidy.
CREATE UNIQUE INDEX ux_payout_account_default
    ON seller_payout_account (seller_profile_id)
    WHERE is_default;
