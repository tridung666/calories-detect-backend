package com.tridung.caloriesdetect.dto.request.meal;

import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

@Schema(description = "Nutrition values are totals for the supplied quantity, not values per 100 grams")
public record MealItemRequest(
        @NotBlank @Size(max = 255) String inputName,
        @NotNull @DecimalMin(value = "0", inclusive = false)
        @Digits(integer = 8, fraction = 2) BigDecimal quantityGrams,
        @NotNull @DecimalMin("0") @DecimalMax("2147483647") @Digits(integer = 10, fraction = 2) BigDecimal calories,
        @NotNull @DecimalMin("0") @DecimalMax("2147483647") @Digits(integer = 10, fraction = 2) BigDecimal proteinGrams,
        @NotNull @DecimalMin("0") @DecimalMax("2147483647") @Digits(integer = 10, fraction = 2) BigDecimal carbohydrateGrams,
        @NotNull @DecimalMin("0") @DecimalMax("2147483647") @Digits(integer = 10, fraction = 2) BigDecimal fatGrams
) {}
