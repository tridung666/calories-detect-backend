package com.tridung.caloriesdetect.mapper;

import com.tridung.caloriesdetect.dto.request.meal.MealItemRequest;
import com.tridung.caloriesdetect.dto.request.meal.ConfirmMealAnalysisRequest;
import com.tridung.caloriesdetect.dto.response.meal.MealItemResponse;
import com.tridung.caloriesdetect.entity.MealItem;
import org.mapstruct.*;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface MealItemMapper {
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "meal", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    void update(MealItemRequest request, @MappingTarget MealItem item);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "meal", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "inputName", source = "name")
    @Mapping(target = "proteinGrams", source = "protein")
    @Mapping(target = "carbohydrateGrams", source = "carbohydrate")
    @Mapping(target = "fatGrams", source = "fat")
    void update(ConfirmMealAnalysisRequest.Item request, @MappingTarget MealItem item);

    @Mapping(target = "mealId", source = "meal.id")
    MealItemResponse toResponse(MealItem item);
}
