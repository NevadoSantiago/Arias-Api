package com.arias.payments;

import java.util.UUID;

/**
 * Publicado DENTRO de la transacción del webhook cuando un reembolso o
 * contracargo revierte créditos — {@code PaymentEmails} manda la alerta al
 * administrador (diseño §Flujo de datos, tabla de mapeo de estados,
 * "alerta al admin") vía {@code @TransactionalEventListener(AFTER_COMMIT)}.
 */
public record CreditPurchaseReversedEvent(
    UUID purchaseId,
    Long userId,
    String userEmail,
    Integer creditsReversedNow,
    Integer creditsReversedTotal,
    Integer creditAmount,
    boolean fullyReversed
) {}
