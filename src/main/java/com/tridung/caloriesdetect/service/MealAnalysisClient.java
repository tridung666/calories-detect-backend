package com.tridung.caloriesdetect.service;

import com.tridung.caloriesdetect.dto.response.meal.MealAnalysisResponse;

public interface MealAnalysisClient {
    MealAnalysisResponse analyze(Long mealId, String imageUrl);
}
