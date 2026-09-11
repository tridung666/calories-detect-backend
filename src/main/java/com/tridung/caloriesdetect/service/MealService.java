package com.tridung.caloriesdetect.service;

import com.tridung.caloriesdetect.dto.request.meal.MealRequest;
import com.tridung.caloriesdetect.dto.response.meal.MealResponse;

import com.tridung.caloriesdetect.common.enums.MealType;
import com.tridung.caloriesdetect.common.response.PageResponse;
import java.time.LocalDate;

public interface MealService {
    MealResponse mealCreat(MealRequest request);
    PageResponse<MealResponse> getMeals(int pageNo, int pageSize, LocalDate mealDate, MealType mealType);
    MealResponse getMeal(Long id);
    MealResponse updateMeal(Long id, MealRequest request);
    void deleteMeal(Long id);
}
