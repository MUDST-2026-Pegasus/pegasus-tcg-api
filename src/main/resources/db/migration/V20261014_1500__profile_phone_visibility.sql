-- Contact numbers remain private until the account owner explicitly shares them.
ALTER TABLE user_account ADD COLUMN phone_public boolean NOT NULL DEFAULT false;
