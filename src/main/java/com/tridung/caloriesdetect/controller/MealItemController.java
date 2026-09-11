package com.tridung.caloriesdetect.controller;

import com.tridung.caloriesdetect.common.response.BaseResponse;
import com.tridung.caloriesdetect.dto.request.meal.MealItemRequest;
import com.tridung.caloriesdetect.dto.response.meal.MealItemResponse;
import com.tridung.caloriesdetect.service.MealItemService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/meal/{mealId}/items")
@RequiredArgsConstructor
@Tag(name = "Meal Item", description = "Manage items in the current user's meals")
public class MealItemController {
    private final MealItemService service;

    @PostMapping
    @Operation(summary = "Add an item to my meal")
    public BaseResponse<MealItemResponse> create(@PathVariable Long mealId, @Valid @RequestBody MealItemRequest request) {
        return BaseResponse.success(service.create(mealId, request));
    }

    @GetMapping
    @Operation(summary = "List items in my meal")
    public BaseResponse<List<MealItemResponse>> getItems(@PathVariable Long mealId) {
        return BaseResponse.success(service.getItems(mealId));
    }

    @GetMapping("/{itemId}")
    @Operation(summary = "Get an item in my meal")
    public BaseResponse<MealItemResponse> getItem(@PathVariable Long mealId, @PathVariable Long itemId) {
        return BaseResponse.success(service.getItem(mealId, itemId));
    }

    @PutMapping("/{itemId}")
    @Operation(summary = "Replace an item in my meal")
    public BaseResponse<MealItemResponse> update(@PathVariable Long mealId, @PathVariable Long itemId,
                                                @Valid @RequestBody MealItemRequest request) {
        return BaseResponse.success(service.update(mealId, itemId, request));
    }

    @DeleteMapping("/{itemId}")
    @Operation(summary = "Delete an item from my meal")
    public BaseResponse<Void> delete(@PathVariable Long mealId, @PathVariable Long itemId) {
        service.delete(mealId, itemId);
        return BaseResponse.success(null);
    }
}
