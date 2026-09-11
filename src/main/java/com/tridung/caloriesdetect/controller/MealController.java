package com.tridung.caloriesdetect.controller;

import com.tridung.caloriesdetect.common.response.BaseResponse;
import com.tridung.caloriesdetect.dto.request.meal.MealRequest;
import com.tridung.caloriesdetect.dto.response.meal.MealResponse;
import com.tridung.caloriesdetect.service.MealService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import com.tridung.caloriesdetect.common.enums.MealType;
import com.tridung.caloriesdetect.common.response.PageResponse;
import org.springframework.format.annotation.DateTimeFormat;
import java.time.LocalDate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/meal")
@RequiredArgsConstructor
@Tag(name = "Meal", description = "Meal management APIs")
public class MealController {
    private final MealService mealService;

    @Operation(
            summary = "Create Meal",
            description = "Create a meal for the current user"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Meal created successfully",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "success": true,
                                              "code": 200,
                                              "message": "Success",
                                              "data": {
                                                "id": 8,
                                                "mealType": "SNACK",
                                                "mealDate": "1958-06-07"
                                              }
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid request"
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "Unauthorized"
            )
    })
    @PostMapping("/create")
    public BaseResponse<MealResponse> createMeal(
            @Valid @RequestBody MealRequest request
    ) {
        return BaseResponse.success(mealService.mealCreat(request));
    }


    @Operation(summary = "List my meals", description = "Optional date/type filters; zero-based pageNo, pageSize from 1 to 100")
    @GetMapping
    public BaseResponse<PageResponse<MealResponse>> getMeals(
            @RequestParam(defaultValue = "0") int pageNo,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate mealDate,
            @RequestParam(required = false) MealType mealType
    ) {
        return BaseResponse.success(mealService.getMeals(pageNo, pageSize, mealDate, mealType));
    }

    @Operation(summary = "Get my meal")
    @GetMapping("/{id}")
    public BaseResponse<MealResponse> getMeal(@PathVariable Long id) {
        return BaseResponse.success(mealService.getMeal(id));
    }

    @Operation(summary = "Update my meal")
    @PutMapping("/{id}")
    public BaseResponse<MealResponse> updateMeal(@PathVariable Long id, @Valid @RequestBody MealRequest request) {
        return BaseResponse.success(mealService.updateMeal(id, request));
    }

    @Operation(summary = "Delete my meal", description = "Also deletes associated meal items")
    @DeleteMapping("/{id}")
    public BaseResponse<Void> deleteMeal(@PathVariable Long id) {
        mealService.deleteMeal(id);
        return BaseResponse.success(null);
    }
}
