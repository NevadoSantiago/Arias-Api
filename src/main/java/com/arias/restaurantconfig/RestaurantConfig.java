package com.arias.restaurantconfig;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.time.LocalTime;

/**
 * RestaurantConfig = singleton (siempre id=1).
 * Guarda la configuración global del restaurant.
 *
 * <p>La constraint en BD garantiza que solo puede haber UNA fila.
 */
@Entity
@Table(name = "restaurant_config")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RestaurantConfig {

    /** Siempre 1 — enforced por CHECK constraint en la BD. */
    @Id
    private Long id;

    /** Hora del día a la que se cierran los pedidos (corte). */
    @Column(name = "hora_corte", nullable = false)
    private LocalTime horaCorte;

    @Column(nullable = false, length = 50)
    private String timezone = "America/Argentina/Buenos_Aires";

    /**
     * Único valor de tiempo de preparación (unidad 8, migración V20): define
     * a la vez el retiro más temprano ofrecido ({@code now + lead}) y el
     * punto de consumo automático ({@code pickup_at - lead}).
     */
    @Column(name = "pickup_lead_minutes", nullable = false)
    private Integer pickupLeadMinutes;

    /** Días de vencimiento del saldo AVAILABLE de créditos — reemplaza al default fijo de {@code CreditLedgerService}. */
    @Column(name = "credit_expiry_days", nullable = false)
    private Integer creditExpiryDays;

    /** Apertura de la ventana de pedidos. */
    @Column(name = "pickup_window_start", nullable = false)
    private LocalTime pickupWindowStart;

    /** Cierre de la ventana de pedidos (exclusivo). */
    @Column(name = "pickup_window_end", nullable = false)
    private LocalTime pickupWindowEnd;

    /** Paso entre horarios de retiro ofrecidos dentro de la ventana. */
    @Column(name = "pickup_slot_minutes", nullable = false)
    private Integer pickupSlotMinutes;

    /** Horario del resumen matutino de cocina (unidad 12). */
    @Column(name = "daily_summary_time", nullable = false)
    private LocalTime dailySummaryTime;

    /** Minutos antes del retiro para el recordatorio al cliente (unidad 12). */
    @Column(name = "pickup_reminder_minutes", nullable = false)
    private Integer pickupReminderMinutes;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
