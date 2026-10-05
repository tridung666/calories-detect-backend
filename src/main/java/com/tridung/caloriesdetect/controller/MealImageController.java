package com.tridung.caloriesdetect.controller;

import com.tridung.caloriesdetect.common.response.BaseResponse;
import com.tridung.caloriesdetect.dto.response.meal.MealResponse;
import com.tridung.caloriesdetect.service.MealService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/meals/{mealId}/image")
@RequiredArgsConstructor
@Tag(name = "Meal", description = "Meal management APIs")
public class MealImageController {
    private final MealService mealService;

    @PutMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BaseResponse<MealResponse> uploadImage(@PathVariable Long mealId, @RequestPart("file") MultipartFile file) {
        return BaseResponse.success(mealService.uploadImage(mealId, file));
    }

    @DeleteMapping
    public BaseResponse<MealResponse> deleteImage(@PathVariable Long mealId) {
        return BaseResponse.success(mealService.deleteImage(mealId));
    }
}
