package com.tridung.caloriesdetect.service.impl;

import com.tridung.caloriesdetect.dto.request.meal.MealItemRequest;
import com.tridung.caloriesdetect.dto.response.meal.MealItemResponse;
import com.tridung.caloriesdetect.entity.Meal;
import com.tridung.caloriesdetect.entity.MealItem;
import com.tridung.caloriesdetect.exception.AppException;
import com.tridung.caloriesdetect.exception.ErrorCode;
import com.tridung.caloriesdetect.mapper.MealItemMapper;
import com.tridung.caloriesdetect.repository.MealItemRepository;
import com.tridung.caloriesdetect.repository.MealRepository;
import com.tridung.caloriesdetect.security.CurrentUserProvider;
import com.tridung.caloriesdetect.service.MealItemService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MealItemServiceImpl implements MealItemService {
    private final MealRepository mealRepository;
    private final MealItemRepository itemRepository;
    private final MealItemMapper mapper;
    private final CurrentUserProvider currentUserProvider;

    @Override
    @Transactional
    public MealItemResponse create(Long mealId, MealItemRequest request) {
        Meal meal = findOwnedMeal(mealId);
        MealItem item = new MealItem();
        mapper.update(request, item);
        item.setMeal(meal);
        return mapper.toResponse(itemRepository.save(item));
    }

    @Override
    public List<MealItemResponse> getItems(Long mealId) {
        findOwnedMeal(mealId);
        return itemRepository.findAllByMeal_IdOrderByIdAsc(mealId).stream().map(mapper::toResponse).toList();
    }

    @Override
    public MealItemResponse getItem(Long mealId, Long itemId) {
        return mapper.toResponse(findOwnedItem(mealId, itemId));
    }

    @Override
    @Transactional
    public MealItemResponse update(Long mealId, Long itemId, MealItemRequest request) {
        MealItem item = findOwnedItem(mealId, itemId);
        mapper.update(request, item);
        return mapper.toResponse(itemRepository.save(item));
    }

    @Override
    @Transactional
    public void delete(Long mealId, Long itemId) {
        itemRepository.delete(findOwnedItem(mealId, itemId));
    }

    private Meal findOwnedMeal(Long mealId) {
        return mealRepository.findByIdAndUserId_Id(mealId, currentUserProvider.getCurrentUserId())
                .orElseThrow(() -> new AppException(ErrorCode.MEAL_NOT_FOUND));
    }

    private MealItem findOwnedItem(Long mealId, Long itemId) {
        findOwnedMeal(mealId);
        return itemRepository.findByIdAndMeal_Id(itemId, mealId)
                .orElseThrow(() -> new AppException(ErrorCode.MEAL_ITEM_NOT_FOUND));
    }
}
