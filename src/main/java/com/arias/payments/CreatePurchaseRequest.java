package com.arias.payments;

import jakarta.validation.constraints.NotNull;

/**
 * Body de {@code POST /api/v1/credits/purchases}. Exactamente uno de
 * {@code packId}/{@code orderId} debe venir, según {@code type} — el
 * importe NUNCA viaja en el request, se calcula siempre en el servidor
 * (diseño §Seguridad).
 */
public record CreatePurchaseRequest(
    @NotNull PurchaseType type,
    Long packId,
    Long orderId
) {}
