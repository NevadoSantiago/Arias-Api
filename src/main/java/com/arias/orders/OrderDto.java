package com.arias.orders;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record OrderDto(
    Long id,
    LocalDate fecha,
    Instant pickupAt,
    OrderEstado estado,
    Integer creditTotal,
    String notas,
    List<OrderItemDto> items,
    /**
     * Si el pedido se puede cancelar EN ESTE MOMENTO ({@code estado ==
     * PENDIENTE && now < pickupAt - lead}, con {@code lead} leído de {@code
     * restaurant_config}) — calculado en el backend para que el frontend
     * nunca reimplemente la regla del deadline de cancelación (gap fix
     * "mis pedidos": esa regla ya divergió una vez en este proyecto). Ver
     * {@code OrderPlacementService#isCancellable}.
     */
    boolean cancellable
) {
    public static OrderDto from(Order order, boolean cancellable) {
        return new OrderDto(
            order.getId(),
            order.getFecha(),
            order.getPickupAt(),
            order.getEstado(),
            order.getCreditTotal(),
            order.getNotas(),
            order.getItems().stream().map(OrderItemDto::from).toList(),
            cancellable
        );
    }
}
