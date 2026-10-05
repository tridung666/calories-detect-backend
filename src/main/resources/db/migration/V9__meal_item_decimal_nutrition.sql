ALTER TABLE meal_items
    ALTER COLUMN calories TYPE NUMERIC(12, 2) USING calories::NUMERIC(12, 2),
    ALTER COLUMN protein_grams TYPE NUMERIC(12, 2) USING protein_grams::NUMERIC(12, 2),
    ALTER COLUMN carbohydrate_grams TYPE NUMERIC(12, 2) USING carbohydrate_grams::NUMERIC(12, 2),
    ALTER COLUMN fat_grams TYPE NUMERIC(12, 2) USING fat_grams::NUMERIC(12, 2);
