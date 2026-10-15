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
     * Tiempo de preparación (unidad 8, migración V20). Desde V34 es solo el
     * lado administrativo: define el punto de consumo automático
     * ({@code pickup_at - lead}) en que un pedido programado pasa a
     * CONFIRMADO y la cocina lo ve. El mínimo para pedir es
     * {@link #orderLeadMinutes} y el límite para cancelar es
     * {@link #cancelLimitMinutes}.
     */
    @Column(name = "pickup_lead_minutes", nullable = false)
    private Integer pickupLeadMinutes;

    /**
     * "Lo antes posible" (migración V34): mínimo de anticipación para pedir;
     * el primer horario de retiro ofrecido es {@code now + orderLead}.
     */
    @Column(name = "order_lead_minutes", nullable = false)
    private Integer orderLeadMinutes;

    /**
     * Límite de cancelación (migración V34): hasta cuántos minutos antes del
     * retiro se puede cancelar o cambiar el pedido.
     */
    @Column(name = "cancel_limit_minutes", nullable = false)
    private Integer cancelLimitMinutes;

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
