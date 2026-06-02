package com.arias.catalog.categories;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.Map;

public record UpdateCategoryRequest(
    @NotBlank @Size(max = 100) String nombre,
    Long parentId,
    @NotNull @PositiveOrZero Integer ordenDisplay,
    @NotNull Boolean enabled,
    /** Precios por empresa. Required: una entry por cada empresa existente. */
    @NotNull Map<Long, Integer> companyPrices
) {}
