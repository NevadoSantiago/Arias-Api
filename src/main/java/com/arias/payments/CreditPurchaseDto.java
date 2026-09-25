package com.arias.payments;

import java.time.Instant;
import java.util.UUID;

public record CreditPurchaseDto(
    UUID id,
    PurchaseType type,
    Integer creditAmount,
    Long amountCents,
    String currency,
    CreditPurchaseStatus status,
    Instant createdAt,
    Instant creditedAt,
    Instant reversedAt,
    String packNombre
) {
    /**
     * {@code packNombre} es el {@code nombre} del paquete comprado (p. ej.
     * "Paquete Semana") para compras {@code PACK}, {@code null} en {@code
     * DIRECT} — el frontend lo usa en vez de un texto genérico (feature
     * b2c-ordering-redesign, tarea B3). {@code p.getPack()} es {@code
     * LAZY}; esto solo es seguro porque {@code from()} se invoca siempre
     * dentro de la transacción de {@link CreditPurchaseService#getPurchase}.
     */
    public static CreditPurchaseDto from(CreditPurchase p) {
        return new CreditPurchaseDto(
            p.getId(),
            p.getType(),
            p.getCreditAmount(),
            p.getAmountCents(),
            p.getCurrency(),
            p.getStatus(),
            p.getCreatedAt(),
            p.getCreditedAt(),
            p.getReversedAt(),
            p.getPack() != null ? p.getPack().getNombre() : null
        );
    }
}
