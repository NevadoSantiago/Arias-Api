package com.arias.credits;

import com.arias.credits.discount.AccountDiscountDto;

import java.time.Instant;

/**
 * Respuesta de {@code GET /api/v1/credits/wallet}. {@code discount} is the account discount
 * usable right now, or {@code null} (none, expired, already used, or held by a pending purchase).
 */
public record CreditWalletDto(Integer available, Integer committed, Instant expiresAt, AccountDiscountDto discount) {

    public static CreditWalletDto from(CreditWallet wallet, AccountDiscountDto discount) {
        return new CreditWalletDto(wallet.getAvailable(), wallet.getCommitted(), wallet.getExpiresAt(), discount);
    }
}
