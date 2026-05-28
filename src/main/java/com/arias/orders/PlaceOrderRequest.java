package com.arias.orders;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record PlaceOrderRequest(
    @NotNull Long dishId,
    Long sideId,
    @Size(max = 200) String notas,
    LocalDate fecha
) {}
