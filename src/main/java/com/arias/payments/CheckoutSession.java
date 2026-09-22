package com.arias.payments;

/**
 * Resultado de crear un checkout: el id de la preferencia y la URL a la que
 * se redirige al navegador del cliente para completar el pago.
 */
public record CheckoutSession(String preferenceId, String initPoint) {}
