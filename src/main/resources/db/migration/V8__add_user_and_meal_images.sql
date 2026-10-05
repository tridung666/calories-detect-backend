ALTER TABLE users
    ADD COLUMN avatar_public_id VARCHAR(255),
    ADD COLUMN avatar_url TEXT;

ALTER TABLE meals
    ADD COLUMN image_public_id VARCHAR(255),
    ADD COLUMN image_url TEXT;
