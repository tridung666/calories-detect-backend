package com.tridung.caloriesdetect.dto.request.meal;

import com.tridung.caloriesdetect.common.enums.MealType;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record MealRequest (
        @NotNull
        MealType mealType,

        @NotNull
        LocalDate mealDate
) {

}
