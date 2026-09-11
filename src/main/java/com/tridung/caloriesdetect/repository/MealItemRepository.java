package com.tridung.caloriesdetect.repository;

import com.tridung.caloriesdetect.entity.MealItem;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface MealItemRepository extends JpaRepository<MealItem, Long> {
    List<MealItem> findAllByMeal_IdOrderByIdAsc(Long mealId);
    Optional<MealItem> findByIdAndMeal_Id(Long id, Long mealId);
    void deleteAllByMeal_Id(Long mealId);
}
