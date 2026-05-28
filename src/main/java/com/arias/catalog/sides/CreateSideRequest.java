package com.arias.catalog.sides;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateSideRequest(
    @NotBlank @Size(max = 100) String nombre,
    @NotNull SideType tipo
) {}
