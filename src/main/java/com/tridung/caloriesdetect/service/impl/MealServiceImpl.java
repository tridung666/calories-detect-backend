package com.tridung.caloriesdetect.service.impl;

import com.tridung.caloriesdetect.dto.request.meal.MealRequest;
import com.tridung.caloriesdetect.dto.response.meal.MealResponse;
import com.tridung.caloriesdetect.entity.Meal;
import com.tridung.caloriesdetect.entity.User;
import com.tridung.caloriesdetect.exception.AppException;
import com.tridung.caloriesdetect.exception.ErrorCode;
import com.tridung.caloriesdetect.mapper.MealMapper;
import com.tridung.caloriesdetect.repository.MealRepository;
import com.tridung.caloriesdetect.repository.MealItemRepository;
import com.tridung.caloriesdetect.repository.UserRepository;
import com.tridung.caloriesdetect.security.CurrentUserProvider;
import com.tridung.caloriesdetect.service.MealService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import com.tridung.caloriesdetect.common.enums.MealType;
import com.tridung.caloriesdetect.common.response.PageResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class MealServiceImpl implements MealService {
    private final UserRepository userRepository;
    private final MealRepository mealRepository;
    private final MealItemRepository mealItemRepository;
    private final MealMapper mealMapper;
    private final CurrentUserProvider currentUserProvider;

    @Override
    @Transactional
    public MealResponse mealCreat(MealRequest request) {
        Long currentUserId = currentUserProvider.getCurrentUserId();

        User user = userRepository.findById(currentUserId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));

        Meal meal = mealMapper.toEntity(request, user);

        Meal savedMeal = mealRepository.save(meal);

        return mealMapper.toMealResponse(savedMeal);
    }

    @Override
    public PageResponse<MealResponse> getMeals(int pageNo, int pageSize, LocalDate mealDate, MealType mealType) {
        if (pageNo < 0 || pageSize < 1 || pageSize > 100) {
            throw new AppException(ErrorCode.INVALID_MEAL_PAGINATION);
        }
        Long userId = currentUserProvider.getCurrentUserId();
        Specification<Meal> filter = (root, query, cb) -> cb.equal(root.get("userId").get("id"), userId);
        if (mealDate != null) {
            filter = filter.and((root, query, cb) -> cb.equal(root.get("mealDate"), mealDate));
        }
        if (mealType != null) {
            filter = filter.and((root, query, cb) -> cb.equal(root.get("mealType"), mealType));
        }
        var pageable = PageRequest.of(pageNo, pageSize, Sort.by(Sort.Direction.DESC, "mealDate", "id"));
        return PageResponse.from(mealRepository.findAll(filter, pageable), mealMapper::toMealResponse);
    }

    @Override
    public MealResponse getMeal(Long id) {
        return mealMapper.toMealResponse(findOwnedMeal(id));
    }

    @Override
    @Transactional
    public MealResponse updateMeal(Long id, MealRequest request) {
        Meal meal = findOwnedMeal(id);
        meal.setMealType(request.mealType());
        meal.setMealDate(request.mealDate());
        return mealMapper.toMealResponse(mealRepository.save(meal));
    }

    @Override
    @Transactional
    public void deleteMeal(Long id) {
        Meal meal = findOwnedMeal(id);
        mealItemRepository.deleteAllByMeal_Id(id);
        mealRepository.delete(meal);
    }

    private Meal findOwnedMeal(Long id) {
        return mealRepository.findByIdAndUserId_Id(id, currentUserProvider.getCurrentUserId())
                .orElseThrow(() -> new AppException(ErrorCode.MEAL_NOT_FOUND));
    }
}
