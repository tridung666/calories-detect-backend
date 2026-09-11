package com.tridung.caloriesdetect.repository;

import com.tridung.caloriesdetect.entity.Meal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import java.util.Optional;

public interface MealRepository extends JpaRepository<Meal, Long>, JpaSpecificationExecutor<Meal> {
    Optional<Meal> findByIdAndUserId_Id(Long id, Long userId);
}
