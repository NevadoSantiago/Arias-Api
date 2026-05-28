package com.arias.restaurantconfig;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record CreateDisabledDateRequest(
    @NotNull LocalDate fecha,
    @Size(max = 200) String motivo
) {}
