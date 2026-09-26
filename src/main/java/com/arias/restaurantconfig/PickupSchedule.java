package com.arias.restaurantconfig;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalTime;

/**
 * Franja de retiro de un día ISO de la semana (migración V24, decisión de
 * usuario 2026-09-26 — B5/F14): una fila por {@code day_of_week} (1=lunes..
 * 7=domingo, {@link java.time.DayOfWeek#getValue()}), con una sola franja
 * (apertura-cierre) o el día cerrado.
 *
 * <p>Reemplaza a {@code restaurant_config.pickup_window_start/end} (V20) como
 * fuente de verdad para {@link com.arias.orders.PickupSlotService}. Esas dos
 * columnas de {@link RestaurantConfig} se conservan solo por compatibilidad
 * hacia atrás del DTO — ya no determinan la franja real.
 */
@Entity
@Table(name = "pickup_schedule")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PickupSchedule {

    /** ISO: 1=lunes .. 7=domingo. */
    @Id
    @Column(name = "day_of_week")
    private Integer dayOfWeek;

    @Column(name = "open", nullable = false)
    private boolean open;

    /** Apertura de la franja de retiro de este día. {@code null} si el día está cerrado. */
    @Column(name = "window_start")
    private LocalTime windowStart;

    /** Cierre (exclusivo) de la franja de retiro de este día. {@code null} si el día está cerrado. */
    @Column(name = "window_end")
    private LocalTime windowEnd;
}
