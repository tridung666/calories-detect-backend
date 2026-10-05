package com.tridung.caloriesdetect.dto.response.meal;

import java.math.BigDecimal;

public record MealItemResponse(
        Long id,
        Long mealId,
        String inputName,
        BigDecimal quantityGrams,
        BigDecimal calories,
        BigDecimal proteinGrams,
        BigDecimal carbohydrateGrams,
        BigDecimal fatGrams
) {}
