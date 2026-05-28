package com.arias.catalog.menusections;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record UpdateMenuSectionRequest(
    @NotBlank @Size(max = 100) String nombre,
    @NotNull @PositiveOrZero Integer ordenDisplay,
    @NotNull Boolean enabled
) {}
