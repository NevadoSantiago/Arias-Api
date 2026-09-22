package com.arias.payments;

import java.time.Instant;
import java.util.UUID;

public record CreditPurchaseDto(
    UUID id,
    PurchaseType type,
    Integer creditAmount,
    Long amountCents,
    String currency,
    CreditPurchaseStatus status,
    Instant createdAt,
    Instant creditedAt,
    Instant reversedAt
) {
    public static CreditPurchaseDto from(CreditPurchase p) {
        return new CreditPurchaseDto(
            p.getId(),
            p.getType(),
            p.getCreditAmount(),
            p.getAmountCents(),
            p.getCurrency(),
            p.getStatus(),
            p.getCreatedAt(),
            p.getCreditedAt(),
            p.getReversedAt()
        );
    }
}
