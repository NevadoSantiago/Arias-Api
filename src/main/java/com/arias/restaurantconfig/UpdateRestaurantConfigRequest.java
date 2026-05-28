package com.arias.restaurantconfig;

import jakarta.validation.constraints.NotNull;

import java.time.LocalTime;

public record UpdateRestaurantConfigRequest(
    @NotNull LocalTime horaCorte
) {}
