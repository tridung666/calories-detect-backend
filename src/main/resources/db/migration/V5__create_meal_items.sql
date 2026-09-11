CREATE TABLE meal_items (
    id BIGSERIAL PRIMARY KEY,

    meal_id BIGINT NOT NULL,

    input_name VARCHAR(255) NOT NULL,
    normalized_name VARCHAR(255),

    quantity_grams NUMERIC(10, 2) NOT NULL,

    calories INTEGER NOT NULL,
    protein_grams INTEGER NOT NULL,
    carbohydrate_grams INTEGER NOT NULL,
    fat_grams INTEGER NOT NULL,

    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,

    CONSTRAINT fk_meal_items_meal
        FOREIGN KEY (meal_id)
            REFERENCES meals(id)
            ON DELETE CASCADE,

    CONSTRAINT chk_meal_items_quantity_grams
                                CHECK (quantity_grams > 0),

    CONSTRAINT chk_meal_items_nutrition_values
        CHECK (
            calories >= 0
                AND protein_grams >= 0
                AND carbohydrate_grams >= 0
                AND fat_grams >= 0
            )
);

CREATE INDEX idx_meal_items_meal_id
    ON meal_items(meal_id);
