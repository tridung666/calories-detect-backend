package com.tridung.caloriesdetect.dto.request.meal;

import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

@Schema(description = "Nutrition values are totals for the supplied quantity, not values per 100 grams")
public record MealItemRequest(
        @NotBlank @Size(max = 255) String inputName,
        @Size(max = 255) String normalizedName,
        @NotNull @DecimalMin(value = "0", inclusive = false)
        @Digits(integer = 8, fraction = 2) BigDecimal quantityGrams,
        @NotNull @PositiveOrZero Integer calories,
        @NotNull @PositiveOrZero Integer proteinGrams,
        @NotNull @PositiveOrZero Integer carbohydrateGrams,
        @NotNull @PositiveOrZero Integer fatGrams
) {}
