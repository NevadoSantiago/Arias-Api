package com.arias.payments;

/**
 * Datos para iniciar un checkout de Mercado Pago. El importe SIEMPRE se
 * calcula en el servidor (nunca lo manda el cliente) — {@code unitPriceCents}
 * y {@code quantity} deben derivarse de {@code credit_pack.price_cents} o del
 * total del pedido, nunca de un valor recibido en el request HTTP (diseño
 * §Seguridad). {@code externalReference} es nuestro propio id (el de
 * {@code credit_purchase}), usado para reconciliar el webhook con la compra.
 */
public record CheckoutRequest(
    String externalReference,
    String title,
    int quantity,
    long unitPriceCents,
    String payerEmail,
    String successUrl,
    String pendingUrl,
    String failureUrl,
    String notificationUrl
) {}
