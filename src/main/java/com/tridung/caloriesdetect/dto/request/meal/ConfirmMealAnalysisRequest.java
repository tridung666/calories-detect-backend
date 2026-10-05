package com.tridung.caloriesdetect.dto.request.meal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.util.List;

@Schema(description = "Confirmed nutrition totals for each supplied quantity; replaces all existing meal items")
public record ConfirmMealAnalysisRequest(
        @NotEmpty List<@NotNull @Valid Item> items
) {
    @Schema(name = "ConfirmedMealAnalysisItem")
    public record Item(
            @NotBlank @Size(max = 255) String name,
            @NotNull @DecimalMin(value = "0", inclusive = false)
            @Digits(integer = 8, fraction = 2) BigDecimal quantityGrams,
            @NotNull @DecimalMin("0") @DecimalMax("2147483647") @Digits(integer = 10, fraction = 2) BigDecimal calories,
            @NotNull @DecimalMin("0") @DecimalMax("2147483647") @Digits(integer = 10, fraction = 2) BigDecimal protein,
            @NotNull @DecimalMin("0") @DecimalMax("2147483647") @Digits(integer = 10, fraction = 2) BigDecimal carbohydrate,
            @NotNull @DecimalMin("0") @DecimalMax("2147483647") @Digits(integer = 10, fraction = 2) BigDecimal fat
    ) {}
}
