package com.arias.orders;

public record OrderItemDto(
    Long id,
    Long dishId,
    String dishNombre,
    String dishCategoria,
    Long sideId,
    String sideNombre,
    Integer creditCost,
    String notas
) {
    public static OrderItemDto from(OrderItem item) {
        return new OrderItemDto(
            item.getId(),
            item.getDish().getId(),
            item.getDishNombre(),
            item.getDishCategoria(),
            item.getSide() != null ? item.getSide().getId() : null,
            item.getSideNombre(),
            item.getCreditCost(),
            item.getNotas()
        );
    }
}
