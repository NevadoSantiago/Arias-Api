package com.arias.payments;

import java.util.UUID;

/**
 * Publicado DENTRO de la transacción del webhook cuando se acredita una
 * compra — {@code PaymentEmails} lo escucha con {@code
 * @TransactionalEventListener(AFTER_COMMIT)} (mismo patrón que {@code
 * OrderCancelledEvent}, unidad 12): el email nunca sale si la transacción
 * termina en rollback.
 */
public record CreditPurchaseCreditedEvent(
    UUID purchaseId,
    Long userId,
    String userEmail,
    PurchaseType type,
    Integer creditAmount
) {}
