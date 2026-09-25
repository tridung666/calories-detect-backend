ALTER TABLE users ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE otp_tokens
(
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT       NOT NULL,
    purpose       VARCHAR(32)  NOT NULL,
    otp_hash      VARCHAR(255) NOT NULL,
    expires_at    TIMESTAMPTZ  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL,
    is_used       BOOLEAN      NOT NULL DEFAULT FALSE,
    attempt_count INTEGER      NOT NULL DEFAULT 0,

    CONSTRAINT fk_otp_user
        FOREIGN KEY (user_id)
            REFERENCES users (id)
            ON DELETE CASCADE,

    CONSTRAINT chk_otp_attempt_count
        CHECK (attempt_count >= 0),

    CONSTRAINT chk_otp_purpose
        CHECK (purpose IN ('EMAIL_VERIFICATION', 'PASSWORD_RESET'))
);

CREATE INDEX idx_otp_user_id
    ON otp_tokens (user_id);

CREATE INDEX idx_otp_expires_at
    ON otp_tokens (expires_at);

CREATE INDEX idx_otp_user_purpose_latest
    ON otp_tokens (user_id, purpose, id DESC);

CREATE INDEX idx_otp_user_purpose_expiry
    ON otp_tokens (user_id, purpose, expires_at);
