package com.arias.orders.notifications;

import java.time.Instant;

/**
 * Evento de cancelación por corte de un pedido {@code PENDIENTE_PAGO} (unidad
 * B8): el punto {@code pickup_at - lead} llegó sin que Mercado Pago
 * registrara el pago. Publicado por {@code OrderPlacementService.closeAtCutoff()}
 * DENTRO de la transacción y consumido por {@link OrderNotificationScheduler}
 * vía {@code @TransactionalEventListener(phase = AFTER_COMMIT)}.
 *
 * <p>Es distinto de {@link OrderCancelledEvent} a propósito: aquel avisa "tus
 * almuerzos vuelven a tu saldo" (asume almuerzos comprometidos) y también
 * alerta a los administradores; este solo avisa al cliente que el pago no se
 * registró a tiempo. Ni el rechazo/expiración del pago ni la cancelación del
 * propio cliente lo publican — el cliente ya lo ve en la app.
 */
public record OrderUnpaidCancelledEvent(
    Long orderId,
    Long userId,
    String userEmail,
    String userDisplayName,
    Instant pickupAt
) {
}
