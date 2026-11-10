ALTER TABLE user_account ALTER COLUMN password_hash DROP NOT NULL;

CREATE TABLE user_identity (
    id               bigint       PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    user_id          bigint       NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    provider         varchar(20)  NOT NULL,
    provider_subject varchar(255) NOT NULL,
    created_at       timestamptz  NOT NULL DEFAULT now(),
    last_login_at    timestamptz,
    CONSTRAINT uq_user_identity_subject UNIQUE (provider, provider_subject),
    CONSTRAINT uq_user_identity_user_provider UNIQUE (user_id, provider),
    CONSTRAINT ck_user_identity_provider CHECK (provider IN ('GOOGLE'))
);
