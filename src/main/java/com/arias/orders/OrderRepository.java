package com.arias.orders;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
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
     * Mismo {@link #findByIdAndUserId}, pero con {@code SELECT ... FOR UPDATE}
     * — mismo patrón que {@code CreditPurchaseRepository#findByIdForUpdate}
     * (unidad B6.1, hallazgo de revisión). {@link OrderPlacementService#addItems},
     * {@link OrderPlacementService#removeItem} y {@link OrderPlacementService#cancel}
     * cargaban el pedido con {@link #findByIdAndUserId} (sin lock): dos
     * llamadas concurrentes sobre el MISMO pedido pueden leer cada una la
     * misma foto de {@code items}/{@code creditTotal} antes de que la otra
     * confirme su cambio — por ejemplo, dos {@code removeItem} en un pedido
     * de dos ítems pueden ver cada uno {@code items.size() == 2}, así que
     * ninguno toma la rama "es el último ítem", y el pedido termina con CERO
     * ítems pero sigue {@code PENDIENTE} (créditos liberados sin cancelar).
     * Bloquear la fila del pedido serializa esas llamadas: la segunda en
     * llegar espera a que la primera confirme y relee el estado ya
     * actualizado. El orden de lock (pedido primero, billetera después) es el
     * MISMO que ya usan estos tres métodos al llamar a
     * {@code CreditLedgerService} después de esta carga — no cambia, así que
     * no hay riesgo nuevo de deadlock entre ambos locks.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Order o WHERE o.id = :id AND o.user.id = :userId")
    Optional<Order> findByIdAndUserIdForUpdate(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * Mismo {@link #findByIdAndUserIdForUpdate}, sin el scope de {@code
     * userId} — para los dos caminos DEL SISTEMA que deciden sobre un pedido
     * sin tener un cliente autenticado (unidad B7.1, hallazgo de revisión):
     * {@code CreditPurchaseService#creditApprovedPurchase} (aprobación de
     * Mercado Pago) y {@link OrderPlacementService#closeForPaymentFailure}
     * (invocado también por {@code OrderConsumptionScheduler} en su corte).
     * Bloquea la fila ANTES de decidir para que un cancel del cliente y una
     * de estas dos decisiones del sistema nunca lean la misma foto vieja del
     * {@code estado}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Order o WHERE o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") Long id);

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
     * {@code CANCELADO} y, desde la unidad B7, {@code PENDIENTE_PAGO}: el
     * resto de la app los trata como si no existieran (un pedido esperando
     * pago todavía puede no confirmarse nunca).
     */
    List<Order> findByFechaAndEstadoNotIn(LocalDate fecha, Collection<OrderEstado> excluidos);

    /**
     * Pedidos elegibles para el recordatorio de retiro (unidad 12): todavía
     * no se les mandó ({@code reminder_sent_at IS NULL}), no están en
     * {@code excluidos} ({@code CANCELADO} y, desde la unidad B7, {@code
     * PENDIENTE_PAGO} — no tiene sentido recordarle el retiro a un pedido que
     * todavía no se pagó), y su punto de recordatorio ya llegó ({@code
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
        WHERE o.estado NOT IN (:excluidos)
          AND o.reminderSentAt IS NULL
          AND o.pickupAt <= :cutoff
    """)
    List<Order> findByEstadoNotInAndReminderSentAtIsNullAndPickupAtLessThanEqual(
        @Param("excluidos") Collection<OrderEstado> excluidos, @Param("cutoff") Instant cutoff);

    /**
     * Claim atómico del recordatorio de retiro — mismo patrón que {@code
     * dishRepo.decrementStock}: si devuelve 0, otra instancia ya lo mandó (o
     * el pedido se canceló entre el SELECT y este UPDATE). Excluye también
     * {@code PENDIENTE_PAGO} en defensa en profundidad (unidad B7): el
     * llamador ya filtra con {@link
     * #findByEstadoNotInAndReminderSentAtIsNullAndPickupAtLessThanEqual},
     * pero este UPDATE nunca debe reclamar el slot de un pedido sin pagar
     * aunque cambie el llamador en el futuro.
     */
    @Modifying
    @Query("""
        UPDATE Order o SET o.reminderSentAt = :now
        WHERE o.id = :orderId
          AND o.reminderSentAt IS NULL
          AND o.estado <> com.arias.orders.OrderEstado.CANCELADO
          AND o.estado <> com.arias.orders.OrderEstado.PENDIENTE_PAGO
    """)
    int claimReminderSlot(@Param("orderId") Long orderId, @Param("now") Instant now);

    /**
     * Consolidado admin por horario de retiro (unidad 13, spec {@code
     * admin-order-fulfillment}) — mismo criterio de exclusión que {@link
     * #findByFechaAndEstadoNotIn}: {@code CANCELADO} y {@code PENDIENTE_PAGO}
     * no se muestran, la cocina los trata como si no existieran. {@code JOIN
     * FETCH user/items} porque {@link AdminOrderController} arma el DTO
     * agrupado sin abrir una transacción por ítem.
     */
    @Query("""
        SELECT DISTINCT o FROM Order o
        JOIN FETCH o.user
        LEFT JOIN FETCH o.items
        WHERE o.fecha = :fecha
          AND o.estado NOT IN (:excluidos)
        ORDER BY o.pickupAt ASC
    """)
    List<Order> findByFechaAndEstadoNotInOrderByPickupAtAsc(
        @Param("fecha") LocalDate fecha, @Param("excluidos") Collection<OrderEstado> excluidos);

    /**
     * "Mis pedidos" del cliente (gap fix, {@code GET /api/v2/orders}) — scope
     * estricto por {@code userId} (spec: un cliente nunca ve pedidos ajenos).
     * {@code CANCELADO} se incluye a propósito: es soft-cancel, el cliente
     * necesita ver qué pasó con sus créditos, no que el pedido desaparezca.
     *
     * <p>Acotado con {@code Pageable} (no un rango de fechas): sin
     * UNIQUE(user_id, fecha) un usuario puede tener varios pedidos el mismo
     * día, así que un rango de fechas no garantiza un límite de filas — un
     * límite de cantidad sí. Orden por {@code pickupAt DESC} (desempate por
     * {@code id DESC}): los pedidos próximos quedan primero y, entre pasados,
     * el más reciente primero — "más relevante primero" sin exponer historial
     * sin cota.
     */
    @Query("""
        SELECT DISTINCT o FROM Order o
        LEFT JOIN FETCH o.items
        WHERE o.user.id = :userId
        ORDER BY o.pickupAt DESC, o.id DESC
    """)
    List<Order> findRecentByUserId(@Param("userId") Long userId, Pageable pageable);
}
