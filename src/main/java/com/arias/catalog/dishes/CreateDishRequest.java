package com.arias.catalog.dishes;

import com.arias.catalog.sides.SideType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateDishRequest(
    @NotBlank @Size(max = 150) String nombre,
    @Size(max = 2000) String descripcion,
    @Size(max = 500) String fotoUrl,
    @NotNull Long categoryId,
    @NotNull Long menuSectionId,
    /** Null = el plato no lleva acompañamiento. */
    SideType sideType,
    /** Vacío si sideType = null. Si tiene valor, deben ser del mismo tipo. */
    List<Long> allowedSideIds,
    @NotNull @PositiveOrZero Integer stockDiarioDefault,
    @NotNull @PositiveOrZero Integer stockActual,
    Boolean especial
) {}
