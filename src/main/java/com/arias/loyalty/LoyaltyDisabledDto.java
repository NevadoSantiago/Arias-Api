package com.arias.loyalty;

/** Response of {@code GET /api/v1/loyalty} for B2B users and while the feature is off. */
public record LoyaltyDisabledDto(boolean enabled) {

    public static LoyaltyDisabledDto instance() {
        return new LoyaltyDisabledDto(false);
    }
}
