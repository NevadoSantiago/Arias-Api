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
    List<OrderItemDto> items
) {
    public static OrderDto from(Order order) {
        return new OrderDto(
            order.getId(),
            order.getFecha(),
            order.getPickupAt(),
            order.getEstado(),
            order.getCreditTotal(),
            order.getNotas(),
            order.getItems().stream().map(OrderItemDto::from).toList()
        );
    }
}
