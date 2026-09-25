package com.arias.payments;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Body de {@code POST /api/v1/credits/purchases}. Exactamente uno de
 * {@code packId}/{@code orderId} debe venir, según {@code type} — el
 * importe NUNCA viaja en el request, se calcula siempre en el servidor
 * (diseño §Seguridad). {@code quantity} solo aplica a {@code type = PACK} y
 * vale para cualquier pack habilitado: multiplica almuerzos e importe. La app
 * la usa para comprar sueltos sobre el pack {@code DAY} (decisión de usuario
 * 2026-09-25), pero el servidor no lo restringe a ese pack. {@code null}
 * equivale a 1; {@code CreditPurchaseService} rechaza cualquier valor
 * distinto de {@code null}/1 para {@code DIRECT}.
 */
public record CreatePurchaseRequest(
    @NotNull PurchaseType type,
    Long packId,
    Long orderId,
    @Min(1) @Max(10) Integer quantity
) {}
