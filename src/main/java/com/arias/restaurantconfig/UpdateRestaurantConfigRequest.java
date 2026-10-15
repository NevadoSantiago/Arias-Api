package com.arias.restaurantconfig;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.LocalTime;

/**
 * Payload de {@code PUT /api/v1/restaurant-config} — reemplaza el singleton
 * completo, incluyendo los siete campos de configuración B2C agregados en la
 * unidad 8 (migración V20) y los parámetros de la migración V34: tiempo de
 * preparación, "lo antes posible" (mínimo para pedir) y límite de
 * cancelación, vencimiento de créditos, ventana de pedidos y sus derivados.
 */
public record UpdateRestaurantConfigRequest(
    @NotNull LocalTime horaCorte,
    @NotNull @Positive Integer pickupLeadMinutes,
    @NotNull @Positive Integer orderLeadMinutes,
    @NotNull @Positive Integer cancelLimitMinutes,
    @NotNull @Positive Integer creditExpiryDays,
    @NotNull LocalTime pickupWindowStart,
    @NotNull LocalTime pickupWindowEnd,
    @NotNull @Positive Integer pickupSlotMinutes,
    /** Opcional: el resumen matutino está desactivado; si falta se conserva el horario guardado. */
    LocalTime dailySummaryTime,
    @NotNull @Positive Integer pickupReminderMinutes
) {}
