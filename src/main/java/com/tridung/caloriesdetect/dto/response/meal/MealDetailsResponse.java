package com.tridung.caloriesdetect.dto.response.meal;

import com.tridung.caloriesdetect.common.enums.MealType;

import java.time.LocalDate;
import java.util.List;

public record MealDetailsResponse(
        Long id,
        MealType mealType,
        LocalDate mealDate,
        String imageUrl,
        List<MealItemResponse> items
) {}
