package com.arias.payments;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface CreditPurchaseRepository extends JpaRepository<CreditPurchase, UUID> {

    Optional<CreditPurchase> findByIdAndUserId(UUID id, Long userId);

    /**
     * {@code SELECT ... FOR UPDATE} sobre la compra — mismo patrón que
     * {@code CreditWalletRepository#findByIdForUpdate}: el webhook y la
     * reconciliación bloquean esta fila antes de decidir si ya está
     * acreditada (diseño §Flujo de datos, paso 6 "atajo de ya acreditada").
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM CreditPurchase p WHERE p.id = :id")
    Optional<CreditPurchase> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Candidatas a reconciliación (unidad 11, {@code
     * PaymentReconciliationScheduler}): {@code PENDING} creadas antes del
     * corte dado — el scheduler aplica los dos cortes (30 min re-consulta, 24
     * h expira) sobre el mismo resultado, ordenando por antigüedad.
     */
    List<CreditPurchase> findByStatusAndCreatedAtBefore(CreditPurchaseStatus status, Instant cutoff);

    /**
     * ¿Este pedido tiene una compra DIRECTA asociada? (unidad B6.1, hallazgo
     * de revisión). Usado por {@code OrderPlacementService#assertModifiable}
     * para rechazar {@code addItems}/{@code removeItem} sobre un pedido
     * pagado aparte por Mercado Pago: {@code DIRECT_PURCHASE} acredita
     * derecho a {@code committed} sin pasar por {@code available} (ver {@code
     * CreditPurchaseService#creditPurchase}), así que mover
     * {@code available}↔{@code committed} de la billetera por un ítem
     * agregado/quitado — como hacen esos dos métodos para un pedido pagado
     * con saldo propio — desincronizaría el {@code creditTotal} del pedido
     * del importe que la compra DIRECT ya cobra (o va a cobrar) por Mercado
     * Pago.
     *
     * <p>No filtra por {@code status} a propósito: mientras el pedido siga
     * {@code PENDIENTE}, cualquier compra DIRECTA asociada (
     * {@code PENDING}, {@code APPROVED} o {@code IN_MEDIATION}) todavía puede
     * acreditar/haber acreditado créditos sobre el total original. Si la
     * compra ya terminó en {@code REJECTED}/{@code CANCELLED}/{@code EXPIRED},
     * el pedido ya quedó {@code CANCELADO} ({@code
     * CreditPurchaseService#closeAssociatedOrderIfDirect}) y {@code
     * assertModifiable} lo rechaza de todos modos por ese motivo — este
     * método no necesita distinguir ese caso.
     */
    boolean existsByOrderIdAndType(Long orderId, PurchaseType type);

    /**
     * Versión en bloque de {@link #existsByOrderIdAndType} para el listado
     * "mis pedidos" (unidad B10): de los pedidos dados, los ids que tienen una
     * compra del tipo indicado — UNA consulta para todo el listado en vez de
     * una por pedido (N+1). Misma semántica: no filtra por {@code status}.
     */
    @Query("SELECT DISTINCT p.order.id FROM CreditPurchase p WHERE p.order.id IN :orderIds AND p.type = :type")
    Set<Long> findOrderIdsWithPurchaseType(@Param("orderIds") Collection<Long> orderIds,
                                           @Param("type") PurchaseType type);

    /**
     * La compra DIRECT de un pedido dado (unidad B7, resumir un pago
     * abandonado — {@code GET /api/v2/orders/{id}/direct-checkout}): a lo
     * sumo una fila, porque {@code createDirectCheckout} es la única forma de
     * crear una compra {@code DIRECT} y siempre nace atada a un pedido
     * {@code PENDIENTE_PAGO} recién creado.
     */
    Optional<CreditPurchase> findByOrderIdAndType(Long orderId, PurchaseType type);

    /**
     * Compras {@code PENDING} vivas de un usuario (unidad B14, {@code GET
     * /purchases/pending}): creadas en o después de {@code since}, la más
     * nueva primero. El predicado (user_id + status + created_at) lo cubre
     * {@code idx_credit_purchase_user_pending} (V29). {@code LEFT JOIN FETCH}
     * del paquete y del pedido para que el DTO lea {@code packNombre} y
     * {@code orderEstado} sin una consulta por fila.
     */
    @Query("""
        SELECT p FROM CreditPurchase p
        LEFT JOIN FETCH p.pack
        LEFT JOIN FETCH p.order
        WHERE p.user.id = :userId AND p.status = :status AND p.createdAt >= :since
        ORDER BY p.createdAt DESC
        """)
    List<CreditPurchase> findAlivePendingByUser(@Param("userId") Long userId,
                                                @Param("status") CreditPurchaseStatus status,
                                                @Param("since") Instant since);
}
