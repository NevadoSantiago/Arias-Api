package com.arias.orders;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Repositorio de {@link Order} (unidad 7). A diferencia de {@link
 * DailyChoiceRepository#findByUserIdAndFecha}, {@link #findByUserIdAndFecha}
 * devuelve una lista: sin UNIQUE(user_id, fecha), un usuario puede tener
 * varios pedidos el mismo día (spec {@code order-placement}, "Múltiples
 * pedidos por día").
 */
public interface OrderRepository extends JpaRepository<Order, Long> {

    List<Order> findByUserIdAndFecha(Long userId, LocalDate fecha);

    /** Para validar propiedad antes de cancelar — evita el patrón findById + chequeo manual. */
    Optional<Order> findByIdAndUserId(Long id, Long userId);

    /**
     * Pedidos {@code PENDIENTE} cuyo punto de consumo ya llegó ({@code
     * pickup_at - lead <= now}, equivalente a {@code pickup_at <= cutoff} con
     * {@code cutoff = now + lead}) — usado por {@link
     * OrderConsumptionScheduler} (unidad 8, diseño §Decisión 4). La consulta
     * es por {@code pickup_at}, no por "el minuto actual", así que un job que
     * estuvo caído procesa el atraso completo en el primer tick.
     */
    List<Order> findByEstadoAndPickupAtLessThanEqual(OrderEstado estado, Instant cutoff);

    /**
     * Pedidos del día para el resumen matutino (unidad 12) — excluye
     * {@code CANCELADO}, el resto de la app los trata como si no existieran.
     */
    List<Order> findByFechaAndEstadoNot(LocalDate fecha, OrderEstado estadoExcluido);

    /**
     * Pedidos elegibles para el recordatorio de retiro (unidad 12): todavía
     * no se les mandó ({@code reminder_sent_at IS NULL}), no están
     * {@code CANCELADO}, y su punto de recordatorio ya llegó ({@code
     * pickup_at - pickup_reminder_minutes <= now}, equivalente a
     * {@code pickup_at <= cutoff} con {@code cutoff = now + reminderMinutes}
     * — mismo patrón que {@link #findByEstadoAndPickupAtLessThanEqual} para
     * el consumo automático, así un job caído procesa el atraso en el primer
     * tick). {@code JOIN FETCH user/items} porque el scheduler que arma el
     * mail los lee fuera de cualquier transacción larga — el email sale
     * async, nunca dentro de un lock de DB.
     */
    @Query("""
        SELECT DISTINCT o FROM Order o
        JOIN FETCH o.user
        LEFT JOIN FETCH o.items
        WHERE o.estado <> :estadoExcluido
          AND o.reminderSentAt IS NULL
          AND o.pickupAt <= :cutoff
    """)
    List<Order> findByEstadoNotAndReminderSentAtIsNullAndPickupAtLessThanEqual(
        @Param("estadoExcluido") OrderEstado estadoExcluido, @Param("cutoff") Instant cutoff);

    /**
     * Claim atómico del recordatorio de retiro — mismo patrón que {@code
     * dishRepo.decrementStock}: si devuelve 0, otra instancia ya lo mandó (o
     * el pedido se canceló entre el SELECT y este UPDATE).
     */
    @Modifying
    @Query("""
        UPDATE Order o SET o.reminderSentAt = :now
        WHERE o.id = :orderId
          AND o.reminderSentAt IS NULL
          AND o.estado <> com.arias.orders.OrderEstado.CANCELADO
    """)
    int claimReminderSlot(@Param("orderId") Long orderId, @Param("now") Instant now);
}
