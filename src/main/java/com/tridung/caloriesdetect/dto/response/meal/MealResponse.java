package com.tridung.caloriesdetect.dto.response.meal;

import com.tridung.caloriesdetect.common.enums.MealType;

import java.time.LocalDate;

public record MealResponse(
    Long id,
    MealType mealType,
    LocalDate mealDate
) {
}
