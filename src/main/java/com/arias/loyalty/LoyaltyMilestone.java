package com.arias.loyalty;

import java.util.List;

/**
 * The prizes of a cycle, in order. A new prize is a new constant plus handling of its
 * {@link LoyaltyRewardKind} in {@link LoyaltyService}.
 */
public enum LoyaltyMilestone {
    DESSERT_AT_9(9, LoyaltyRewardKind.DESSERT),
    LUNCH_AT_15(15, LoyaltyRewardKind.LUNCH),
    DISCOUNT_AT_24(24, LoyaltyRewardKind.DISCOUNT);

    /** Percent of the discount prize. */
    public static final int DISCOUNT_PERCENT = 10;

    public static final List<LoyaltyMilestone> ALL = List.of(values());

    private final int lunches;
    private final LoyaltyRewardKind kind;

    LoyaltyMilestone(int lunches, LoyaltyRewardKind kind) {
        this.lunches = lunches;
        this.kind = kind;
    }

    public int lunches() {
        return lunches;
    }

    public LoyaltyRewardKind kind() {
        return kind;
    }
}
