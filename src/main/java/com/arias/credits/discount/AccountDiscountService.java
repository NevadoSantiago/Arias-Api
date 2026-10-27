package com.arias.credits.discount;

import com.arias.users.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Grants and looks up the one-time account discount. Reserve / release / consume
 * (used by the purchase flow) are added next to these.
 */
@Service
@RequiredArgsConstructor
public class AccountDiscountService {

    /** How long a granted discount stays usable. */
    public static final Duration VALIDITY = Duration.ofDays(7);

    private final AccountDiscountRepository repo;
    private final Clock clock;

    /**
     * Grants {@code percent}% for {@link #VALIDITY} unless the user already had a discount
     * at any time (used, expired or pending). Returns whether it was granted now.
     */
    @Transactional
    public boolean grantIfAbsent(User user, int percent, String source) {
        Instant now = clock.instant();
        return repo.insertIfAbsent(user.getId(), percent, now, now.plus(VALIDITY), source) == 1;
    }

    /** The discount the user can use at {@code now}: unexpired, unconsumed and not held by a live purchase. */
    @Transactional(readOnly = true)
    public Optional<AccountDiscountDto> findActive(Long userId, Instant now) {
        return repo.findByUserId(userId)
            .filter(d -> isUsable(d, now))
            .map(AccountDiscountDto::from);
    }

    static boolean isUsable(AccountDiscount d, Instant now) {
        return d.getConsumedAt() == null
            && d.getReservedPurchaseId() == null
            && now.isBefore(d.getExpiresAt());
    }
}
