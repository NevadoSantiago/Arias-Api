package com.arias.credits;

import java.util.UUID;

/**
 * Referencia opcional al origen de un movimiento del libro mayor. {@code
 * orderId}/{@code purchaseId} son las mismas FKs lógicas de {@link
 * CreditMovement} — {@code orders} (unidad 7) y {@code credit_purchase}
 * (unidad 11) todavía no existen cuando esta unidad se implementa, así que
 * no hay constraint de base, solo el dato si el llamador lo tiene.
 *
 * <p>{@code description} alimenta el historial legible de {@code GET
 * /api/v1/credits/movements}.
 */
public record MovementRef(Long orderId, UUID purchaseId, String description) {

    public static MovementRef none(String description) {
        return new MovementRef(null, null, description);
    }

    public static MovementRef forOrder(Long orderId, String description) {
        return new MovementRef(orderId, null, description);
    }

    public static MovementRef forPurchase(UUID purchaseId, String description) {
        return new MovementRef(null, purchaseId, description);
    }
}
