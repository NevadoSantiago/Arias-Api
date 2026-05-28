package com.arias.orders;

import java.time.LocalDate;
import java.time.LocalTime;

public record DailyChoiceDto(
    Long id,
    LocalDate fecha,
    OrderEstado estado,
    Long dishId,
    String dishNombre,
    String dishCategoria,
    Long sideId,
    String sideNombre,
    String notas,
    LocalTime horaEntrega,
    /** Si el plato actual está disponible — si es false, el empleado debe modificar. */
    Boolean dishEnabled,
    /** Si el side actual está disponible — null cuando el pedido no tiene side. */
    Boolean sideEnabled
) {
    public static DailyChoiceDto from(DailyChoice c) {
        return new DailyChoiceDto(
            c.getId(),
            c.getFecha(),
            c.getEstado(),
            c.getDish().getId(),
            c.getDishNombre(),
            c.getDishCategoria(),
            c.getSide() != null ? c.getSide().getId() : null,
            c.getSideNombre(),
            c.getNotas(),
            c.getHoraEntrega(),
            c.getDish().getEnabled(),
            c.getSide() != null ? c.getSide().getEnabled() : null
        );
    }
}
