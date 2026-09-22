package com.arias.orders;

import org.springframework.data.jpa.repository.JpaRepository;

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
}
