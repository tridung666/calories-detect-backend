package com.tridung.caloriesdetect.dto.response.meal;

import java.math.BigDecimal;

public record MealItemResponse(
        Long id,
        Long mealId,
        String inputName,
        String normalizedName,
        BigDecimal quantityGrams,
        Integer calories,
        Integer proteinGrams,
        Integer carbohydrateGrams,
        Integer fatGrams
) {}
