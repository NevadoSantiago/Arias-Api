package com.arias.orders;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Payload de {@code POST /api/v2/orders/{id}/items} (unidad B6) — agrega
 * ítems a un pedido existente mientras sea MODIFICABLE (misma regla que
 * {@code cancellable}, decisión de usuario 2026-09-27). Mismo shape de ítem
 * que {@link PlaceOrderV2Request}, reutilizado a propósito para no duplicar
 * la forma del payload.
 */
public record AddOrderItemsRequest(
    @NotEmpty @Valid List<PlaceOrderV2Request.OrderItemRequest> items
) {}
