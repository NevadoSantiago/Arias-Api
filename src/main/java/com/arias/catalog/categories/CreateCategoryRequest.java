package com.arias.catalog.categories;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.Map;

public record CreateCategoryRequest(
    @NotBlank @Size(max = 100) String nombre,
    Long parentId,
    @NotNull @PositiveOrZero Integer ordenDisplay,
    /** Costo en créditos ("almuerzos"). Entero positivo — validado en CategoryService. */
    @NotNull Integer creditCost,
    /** Precios acordados por empresa. Required: una entry por cada empresa existente. */
    @NotNull Map<Long, Integer> companyPrices
) {}
