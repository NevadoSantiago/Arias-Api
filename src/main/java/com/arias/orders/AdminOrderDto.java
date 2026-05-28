package com.arias.orders;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * DTO enriquecido para la vista del SUPER_ADMIN — incluye nombre del empleado
 * y empresa para que el resto vea quién pidió qué.
 */
public record AdminOrderDto(
    Long id,
    LocalDate fecha,
    OrderEstado estado,

    // Datos del que pidió
    Long userId,
    String userFirstName,
    String userLastName,
    String userEmail,

    // Empresa (snapshot del nombre no, vamos vivo porque el admin quiere el actual)
    Long companyId,
    String companyName,

    // Pedido (snapshots)
    Long dishId,
    String dishNombre,
    String dishCategoria,
    String sideNombre,
    String notas,
    LocalTime horaEntrega
) {
    public static AdminOrderDto from(DailyChoice c) {
        var user = c.getUser();
        var company = c.getCompany();
        return new AdminOrderDto(
            c.getId(),
            c.getFecha(),
            c.getEstado(),
            user.getId(),
            user.getFirstName(),
            user.getLastName(),
            user.getEmail(),
            company.getId(),
            company.getNombre(),
            c.getDish().getId(),
            c.getDishNombre(),
            c.getDishCategoria(),
            c.getSideNombre(),
            c.getNotas(),
            c.getHoraEntrega()
        );
    }
}
