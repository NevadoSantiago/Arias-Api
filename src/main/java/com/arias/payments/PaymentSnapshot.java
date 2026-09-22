package com.arias.payments;

/**
 * Fotografía del estado de un pago tal como lo devuelve
 * {@code PaymentClient.get(id)} — la ÚNICA fuente de verdad; nunca se acredita
 * a partir del cuerpo del webhook ni de {@code back_urls} (diseño §Seguridad).
 *
 * <p>{@code amountRefundedCents} (unidad 11, resolución de la unidad 9 —
 * decisión del orquestador): Mercado Pago reporta el reembolso ACUMULADO de
 * un pago vía {@code transaction_amount_refunded}, tanto si el reembolso es
 * parcial (el pago puede seguir {@code approved}) como si es total (el pago
 * pasa a {@code refunded}). La reversión de créditos se calcula siempre a
 * partir de este campo — nunca del string de estado — para cubrir ambos
 * casos y los reembolsos parciales encadenados con una sola fórmula.
 */
public record PaymentSnapshot(
    String paymentId,
    PaymentStatus status,
    String statusDetail,
    long amountCents,
    String currency,
    String externalReference,
    long amountRefundedCents
) {}
