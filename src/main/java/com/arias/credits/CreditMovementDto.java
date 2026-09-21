package com.arias.credits;

import java.time.Instant;
import java.util.UUID;

/** Ítem de la respuesta de {@code GET /api/v1/credits/movements}. */
public record CreditMovementDto(
    Long id,
    MovementType type,
    Integer deltaAvailable,
    Integer deltaCommitted,
    Long orderId,
    UUID purchaseId,
    String description,
    Instant createdAt
) {

    public static CreditMovementDto from(CreditMovement movement) {
        return new CreditMovementDto(
            movement.getId(),
            movement.getType(),
            movement.getDeltaAvailable(),
            movement.getDeltaCommitted(),
            movement.getOrderId(),
            movement.getPurchaseId(),
            movement.getDescription(),
            movement.getCreatedAt()
        );
    }
}
