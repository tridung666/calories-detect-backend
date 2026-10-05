package com.tridung.caloriesdetect.repository;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import com.tridung.caloriesdetect.entity.Meal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import java.util.Optional;

public interface MealRepository extends JpaRepository<Meal, Long>, JpaSpecificationExecutor<Meal> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Meal m where m.id = :id and m.userId.id = :userId")
    Optional<Meal> findOwnedByIdForUpdate(@Param("id") Long id,
                                        @Param("userId") Long userId);

    Optional<Meal> findByIdAndUserId_Id(Long id, Long userId);
}
