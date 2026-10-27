package com.arias.orders;

import org.springframework.data.jpa.repository.JpaRepository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Repositorio de {@link OrderItem} (unidad 7). {@link OrderPlacementService}
 * persiste los ítems por cascada desde {@link Order#addItem}; este repo
 * existe para lecturas directas (reportes/admin de unidades posteriores).
 */
public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    List<OrderItem> findByOrderId(Long orderId);

    /**
     * Dish names of the user's latest ENTREGADO order (by deliveredAt, then pickupAt, then id), first item
     * first. One query: empty means the user has no delivered order. Callers take the first element.
     */
    @Query("""
        SELECT i.dishNombre FROM OrderItem i
        WHERE i.order.id = (
            SELECT o.id FROM Order o
            WHERE o.user.id = :userId AND o.estado = com.arias.orders.OrderEstado.ENTREGADO
            ORDER BY o.deliveredAt DESC NULLS LAST, o.pickupAt DESC, o.id DESC
            LIMIT 1)
        ORDER BY i.id ASC
        """)
    List<String> findDishNamesOfLatestDelivered(@Param("userId") Long userId);
}
