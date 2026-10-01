package com.arias.payments;

import java.time.Instant;
import java.util.UUID;

/**
 * One purchase in the admin payments report. {@code kind} is the pack type
 * (INDIVIDUAL/SUGERIDO/OTRO) or {@code DIRECT} for a purchase tied to an order;
 * {@code packName} / {@code orderId} are set for the matching kind only.
 * {@code feeCents} / {@code netCents} are null when Mercado Pago did not report them.
 */
public record AdminPaymentRowDto(
    UUID purchaseId,
    Instant occurredAt,
    String customer,
    String kind,
    String packName,
    Long orderId,
    int credits,
    long amountCents,
    CreditPurchaseStatus status,
    String mpPaymentId,
    Long feeCents,
    Long netCents,
    int creditsReversed
) {}
