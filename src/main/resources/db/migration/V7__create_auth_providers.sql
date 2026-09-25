-- This release intentionally targets a V5 database without existing accounts.
-- Stop before dropping credentials if that deployment prerequisite is not met.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM users) THEN
        RAISE EXCEPTION 'Auth provider migration requires an empty users table; migrate existing credentials before upgrading';
    END IF;
END $$;

ALTER TABLE users
    DROP COLUMN password,
    DROP COLUMN google_subject;

CREATE TABLE auth_providers
(
    id               BIGSERIAL PRIMARY KEY,
    user_id          BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    provider         VARCHAR(30) NOT NULL,
    provider_subject VARCHAR(255),
    password_hash    VARCHAR(255),
    created_at       TIMESTAMPTZ NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL,

    CONSTRAINT uk_auth_provider_subject UNIQUE (provider, provider_subject),
    CONSTRAINT uk_auth_user_provider UNIQUE (user_id, provider),
    CONSTRAINT chk_auth_provider_credentials CHECK (
        (provider = 'LOCAL' AND password_hash IS NOT NULL AND provider_subject IS NULL)
        OR (provider = 'GOOGLE' AND provider_subject IS NOT NULL AND password_hash IS NULL)
    )
);
