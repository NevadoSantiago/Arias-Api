package com.arias.orders;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * Payload de {@code PATCH /api/v2/orders/{id}/pickup-time} (unidad B11,
 * pedido del usuario 2026-09-28) — el horario de retiro nuevo, como instante
 * ISO. Se valida en {@link OrderPlacementService#changePickupTime}.
 */
public record ChangePickupTimeRequest(
    @NotNull Instant pickupAt
) {}
