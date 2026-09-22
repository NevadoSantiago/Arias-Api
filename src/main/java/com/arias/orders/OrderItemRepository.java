package com.arias.orders;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Repositorio de {@link OrderItem} (unidad 7). {@link OrderPlacementService}
 * persiste los ítems por cascada desde {@link Order#addItem}; este repo
 * existe para lecturas directas (reportes/admin de unidades posteriores).
 */
public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    List<OrderItem> findByOrderId(Long orderId);
}
