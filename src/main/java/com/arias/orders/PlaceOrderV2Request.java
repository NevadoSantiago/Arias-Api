package com.arias.orders;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * Payload de {@code POST /api/v2/orders} (unidad 7) — reemplaza a {@link
 * PlaceOrderRequest} para el camino nuevo por créditos: múltiples ítems por
 * pedido (spec {@code order-placement}, "Pedidos con múltiples ítems") y
 * horario de retiro explícito en vez de "hoy implícito".
 */
public record PlaceOrderV2Request(
    @NotEmpty @Valid List<OrderItemRequest> items,
    @NotNull Instant pickupAt,
    @Size(max = 200) String notas,
    /** Optional: id of the dessert to redeem with the pending loyalty dessert reward (gift line, no credits). */
    Long rewardDessertId
) {
    public PlaceOrderV2Request(List<OrderItemRequest> items, Instant pickupAt, String notas) {
        this(items, pickupAt, notas, null);
    }

    public record OrderItemRequest(
        @NotNull Long dishId,
        Long sideId,
        @Size(max = 200) String notas
    ) {}
}
