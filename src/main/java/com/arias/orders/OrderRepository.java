package com.arias.orders;

import org.springframework.data.jpa.repository.JpaRepository;

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
}
