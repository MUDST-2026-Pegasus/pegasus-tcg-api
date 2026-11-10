ALTER TABLE user_account ADD COLUMN banner_url varchar(500);

CREATE UNIQUE INDEX ux_user_account_banner_url
    ON user_account (banner_url) WHERE banner_url IS NOT NULL;
