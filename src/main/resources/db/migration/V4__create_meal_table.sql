CREATE TABLE meals (
    id BIGSERIAL PRIMARY KEY,

    user_id BIGINT NOT NULL,
    meal_type VARCHAR(20) NOT NULL,
    meal_date DATE NOT NULL,
    CONSTRAINT fk_meals_user
        FOREIGN KEY (user_id)
            REFERENCES users(id)
            ON DELETE CASCADE,

    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_meals_user_id
    ON meals(user_id);

CREATE INDEX idx_meals_user_date
    ON meals(user_id, meal_date);
