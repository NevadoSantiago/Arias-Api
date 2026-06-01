package com.arias.catalog.categories;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record CreateCategoryRequest(
    @NotBlank @Size(max = 100) String nombre,
    Long parentId,
    @NotNull @PositiveOrZero Integer ordenDisplay
) {}
