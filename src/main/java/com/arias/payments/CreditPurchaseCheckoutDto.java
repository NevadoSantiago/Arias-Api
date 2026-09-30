package com.arias.payments;

import java.util.UUID;

/** Respuesta de {@code POST /api/v1/credits/purchases}: a dónde redirigir al usuario para pagar. */
public record CreditPurchaseCheckoutDto(UUID purchaseId, String initPoint) {}
