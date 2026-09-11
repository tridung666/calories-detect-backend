package com.tridung.caloriesdetect.service;

import com.tridung.caloriesdetect.dto.request.meal.MealItemRequest;
import com.tridung.caloriesdetect.dto.response.meal.MealItemResponse;
import java.util.List;

public interface MealItemService {
    MealItemResponse create(Long mealId, MealItemRequest request);
    List<MealItemResponse> getItems(Long mealId);
    MealItemResponse getItem(Long mealId, Long itemId);
    MealItemResponse update(Long mealId, Long itemId, MealItemRequest request);
    void delete(Long mealId, Long itemId);
}
