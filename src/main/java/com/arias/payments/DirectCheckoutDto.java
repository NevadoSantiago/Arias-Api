package com.arias.payments;

import java.util.UUID;

/**
 * Respuesta de {@code POST /api/v2/orders/direct-checkout} y de {@code GET
 * /api/v2/orders/{id}/direct-checkout} (unidad B7) — a diferencia de {@link
 * CreditPurchaseCheckoutDto} (compra de créditos "sueltos"), esta respuesta
 * lleva también {@code orderId}: el pedido y la compra DIRECT nacen juntos,
 * en la misma transacción.
 */
public record DirectCheckoutDto(Long orderId, UUID purchaseId, String initPoint) {}
