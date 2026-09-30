package com.arias.payments;

import com.arias.orders.OrderEstado;

import java.time.Instant;
import java.util.UUID;

public record CreditPurchaseDto(
    UUID id,
    PurchaseType type,
    Integer creditAmount,
    Long amountCents,
    String currency,
    CreditPurchaseStatus status,
    Instant createdAt,
    Instant creditedAt,
    Instant reversedAt,
    String packNombre,
    Long orderId,
    OrderEstado orderEstado
) {
    /**
     * {@code packNombre} es el {@code nombre} del paquete comprado (p. ej.
     * "Paquete Semana") para compras {@code PACK}, {@code null} en {@code
     * DIRECT} — el frontend lo usa en vez de un texto genérico (feature
     * b2c-ordering-redesign, tarea B3).
     *
     * <p>{@code orderId} es el pedido de una compra {@code DIRECT} ({@code
     * null} en {@code PACK}); leer solo el id de la relación {@code LAZY} no
     * la inicializa (tarea B14). Nunca se expone {@code initPoint} ni ningún
     * dato del proveedor de pago.
     *
     * <p>{@code orderEstado} es el estado de ese pedido (p. ej. {@code
     * PENDIENTE_PAGO} o {@code CANCELADO}), {@code null} en {@code PACK}:
     * el aviso de pago pendiente lo usa para distinguir un pedido ya
     * cancelado.
     *
     * <p>{@code getPack()} y {@code getOrder()} son {@code LAZY}: leer el nombre
     * del paquete y el estado del pedido los inicializa. Por eso el llamador debe
     * traer la compra con ambos ya cargados ({@code LEFT JOIN FETCH} en {@link
     * CreditPurchaseRepository#findDetailByIdAndUserId} y {@link
     * CreditPurchaseRepository#findAlivePendingByUser}) — una consulta en total,
     * sin N+1 — y llamar a este método dentro de la transacción de lectura del
     * servicio. Con una compra cargada por otro camino, leer estos dos campos
     * dispara una consulta por cada uno (unidad B14.1).
     */
    public static CreditPurchaseDto from(CreditPurchase p) {
        return new CreditPurchaseDto(
            p.getId(),
            p.getType(),
            p.getCreditAmount(),
            p.getAmountCents(),
            p.getCurrency(),
            p.getStatus(),
            p.getCreatedAt(),
            p.getCreditedAt(),
            p.getReversedAt(),
            p.getPack() != null ? p.getPack().getNombre() : null,
            p.getOrder() != null ? p.getOrder().getId() : null,
            p.getOrder() != null ? p.getOrder().getEstado() : null
        );
    }
}
