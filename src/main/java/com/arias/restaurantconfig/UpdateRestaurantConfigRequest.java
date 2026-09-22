package com.arias.restaurantconfig;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.LocalTime;

/**
 * Payload de {@code PUT /api/v1/restaurant-config} — reemplaza el singleton
 * completo, incluyendo los siete campos de configuración B2C agregados en la
 * unidad 8 (migración V20): tiempo de preparación único, vencimiento de
 * créditos, ventana de pedidos y sus derivados.
 */
public record UpdateRestaurantConfigRequest(
    @NotNull LocalTime horaCorte,
    @NotNull @Positive Integer pickupLeadMinutes,
    @NotNull @Positive Integer creditExpiryDays,
    @NotNull LocalTime pickupWindowStart,
    @NotNull LocalTime pickupWindowEnd,
    @NotNull @Positive Integer pickupSlotMinutes,
    @NotNull LocalTime dailySummaryTime,
    @NotNull @Positive Integer pickupReminderMinutes
) {}
