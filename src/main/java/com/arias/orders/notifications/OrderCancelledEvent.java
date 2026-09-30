package com.arias.orders.notifications;

import java.time.Instant;

/**
 * Evento de cancelación de un {@code Order} (unidad 12, diseño §Decisión
 * 11). Publicado por {@code OrderPlacementService.cancel()} DENTRO de la
 * transacción; {@link OrderNotificationScheduler} lo escucha vía {@code
 * @TransactionalEventListener(phase = AFTER_COMMIT)} para que una
 * cancelación que termina en rollback nunca dispare el mail.
 *
 * <p>Lleva una instantánea de los datos necesarios para el mail (en vez de
 * volver a leer el pedido) porque al momento en que el listener corre — ya
 * después del commit — la transacción original terminó y el pedido puede no
 * estar disponible en el mismo contexto de persistencia.
 */
public record OrderCancelledEvent(
    Long orderId,
    Long userId,
    String userEmail,
    String userDisplayName,
    Instant pickupAt,
    Integer creditTotal
) {
}
