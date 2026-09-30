package com.arias.restaurantconfig;

import java.time.LocalTime;

/**
 * Payload de un día para {@code PUT /api/v1/restaurant-config/pickup-schedule}.
 * La validación cruzada (7 días exactos, sin duplicados, horarios cuando el
 * día está abierto, cierre después de apertura) se hace a mano en el
 * controller — no es expresable con anotaciones simples de Bean Validation.
 */
public record PickupScheduleDayRequest(
    Integer dayOfWeek,
    boolean open,
    LocalTime windowStart,
    LocalTime windowEnd
) {}
