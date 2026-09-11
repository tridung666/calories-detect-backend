package com.tridung.caloriesdetect.mapper;

import com.tridung.caloriesdetect.dto.request.meal.MealRequest;
import com.tridung.caloriesdetect.dto.response.meal.MealResponse;
import com.tridung.caloriesdetect.entity.Meal;
import com.tridung.caloriesdetect.entity.User;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface MealMapper {
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "userId", source = "user")
    Meal toEntity(MealRequest request, User user);

    MealResponse toMealResponse(Meal meal);
}
