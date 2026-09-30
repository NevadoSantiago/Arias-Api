package com.arias.credits;

import java.time.Instant;

/** Respuesta de {@code GET /api/v1/credits/wallet}. */
public record CreditWalletDto(Integer available, Integer committed, Instant expiresAt) {

    public static CreditWalletDto from(CreditWallet wallet) {
        return new CreditWalletDto(wallet.getAvailable(), wallet.getCommitted(), wallet.getExpiresAt());
    }
}
