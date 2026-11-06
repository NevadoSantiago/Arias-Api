package com.arias.catalog.desserts;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record UpdateDessertRequest(
    @NotBlank @Size(max = 150) String nombre,
    @Size(max = 2000) String descripcion,
    @Size(max = 500) String fotoUrl,
    @NotNull @PositiveOrZero Integer stockDiarioDefault,
    /** Null = no tocar el stock de hoy. */
    @PositiveOrZero Integer stockActual,
    @NotNull Boolean enabled
) {}
