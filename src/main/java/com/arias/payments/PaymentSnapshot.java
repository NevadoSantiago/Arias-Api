package com.arias.payments;

/**
 * Fotografía del estado de un pago tal como lo devuelve
 * {@code PaymentClient.get(id)} — la ÚNICA fuente de verdad; nunca se acredita
 * a partir del cuerpo del webhook ni de {@code back_urls} (diseño §Seguridad).
 */
public record PaymentSnapshot(
    String paymentId,
    PaymentStatus status,
    String statusDetail,
    long amountCents,
    String currency,
    String externalReference
) {}
