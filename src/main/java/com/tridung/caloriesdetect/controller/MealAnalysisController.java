package com.tridung.caloriesdetect.controller;

import com.tridung.caloriesdetect.common.response.BaseResponse;
import com.tridung.caloriesdetect.dto.request.meal.ConfirmMealAnalysisRequest;
import com.tridung.caloriesdetect.dto.response.meal.MealAnalysisResponse;
import com.tridung.caloriesdetect.dto.response.meal.MealDetailsResponse;
import com.tridung.caloriesdetect.service.MealService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/meals/{mealId}")
@RequiredArgsConstructor
@Tag(name = "Meal", description = "Meal management APIs")
public class MealAnalysisController {
    private final MealService mealService;

    @Operation(summary = "Analyze meal image", description = "Returns a temporary AI prediction without saving meal items. Requires an owned meal with an image and Bearer authentication.")
    @PostMapping("/analyze")
    public BaseResponse<MealAnalysisResponse> analyze(@PathVariable Long mealId) {
        return BaseResponse.success(mealService.analyze(mealId));
    }

    @Operation(summary = "Confirm meal analysis", description = "Validates edited nutrition totals and atomically replaces all existing meal items. Returns meal details with saved items. Uses the path mealId only.")
    @PostMapping("/confirm-analysis")
    public BaseResponse<MealDetailsResponse> confirm(@PathVariable Long mealId,
                                                   @Valid @RequestBody ConfirmMealAnalysisRequest request) {
        return BaseResponse.success(mealService.confirmAnalysis(mealId, request));
    }
}
