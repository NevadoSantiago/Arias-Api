package com.arias.credits.discount;

import java.time.Instant;

/** The discount a user can use right now, as exposed by the wallet endpoint. */
public record AccountDiscountDto(int percent, Instant expiresAt) {

    public static AccountDiscountDto from(AccountDiscount d) {
        return new AccountDiscountDto(d.getPercent(), d.getExpiresAt());
    }
}
