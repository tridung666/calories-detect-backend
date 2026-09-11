package com.tridung.caloriesdetect.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

@Entity
@Table(name = "meal_items")
@Getter
@Setter
@NoArgsConstructor
public class MealItem extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "meal_id", nullable = false)
    private Meal meal;

    @Column(name = "input_name", nullable = false, length = 255)
    private String inputName;

    @Column(name = "normalized_name", length = 255)
    private String normalizedName;

    @Column(name = "quantity_grams", nullable = false, precision = 10, scale = 2)
    private BigDecimal quantityGrams;

    @Column(name = "calories", nullable = false)
    private Integer calories;

    @Column(name = "protein_grams", nullable = false)
    private Integer proteinGrams;

    @Column(name = "carbohydrate_grams", nullable = false)
    private Integer carbohydrateGrams;

    @Column(name = "fat_grams", nullable = false)
    private Integer fatGrams;

}
