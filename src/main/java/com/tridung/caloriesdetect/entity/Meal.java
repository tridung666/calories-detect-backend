package com.tridung.caloriesdetect.entity;

import com.tridung.caloriesdetect.common.enums.MealType;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

@Entity
@Table(name = "meals")
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Builder
public class Meal extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "meal_type", nullable = false, length = 20)
    private MealType mealType;

    @Column(name = "meal_date", nullable = false)
    private LocalDate mealDate;
}
