package com.arias.catalog.dishes;

import com.arias.catalog.sides.SideType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

public record UpdateDishRequest(
    @NotBlank @Size(max = 150) String nombre,
    @Size(max = 2000) String descripcion,
    @Size(max = 500) String fotoUrl,
    @NotNull Long categoryId,
    @NotNull Long menuSectionId,
    SideType sideType,
    List<Long> allowedSideIds,
    @NotNull @PositiveOrZero Integer stockDiarioDefault,
    @NotNull @PositiveOrZero Integer stockActual,
    @NotNull Boolean enabled,
    Boolean especial,
    List<DiaSemana> diasSemana
) {}
