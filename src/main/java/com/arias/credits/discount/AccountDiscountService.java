package com.arias.credits.discount;

import com.arias.users.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Grants, looks up and applies the one-time account discount: granted once ever, reserved by a
 * PENDING purchase, consumed when that purchase is approved and released when it closes unpaid.
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

    /**
     * Price after the discount, rounded DOWN to a whole peso (a multiple of 100 cents) in the
     * customer's favour. May be 0 for tiny amounts; callers then skip the discount.
     */
    public static long discountedAmountCents(long listAmountCents, int percent) {
        long discounted = listAmountCents * (100 - percent) / 100;
        return discounted / 100 * 100;
    }

    /**
     * Locks the user's discount row ({@code FOR UPDATE}) and returns it only if it is usable at
     * {@code now}. Joins the caller's transaction so the reservation is atomic with the purchase insert.
     */
    @Transactional
    public Optional<AccountDiscount> lockUsable(Long userId, Instant now) {
        return repo.findByUserIdForUpdate(userId).filter(d -> isUsable(d, now));
    }

    /** Marks the (already locked) discount as held by a live PENDING purchase. */
    @Transactional
    public void reserve(AccountDiscount discount, UUID purchaseId) {
        discount.setReservedPurchaseId(purchaseId);
    }

    /**
     * Frees the reservation held by {@code purchaseId} (rejected, cancelled, expired or never
     * delivered). Idempotent; a reservation held by another purchase, or an already consumed
     * discount, is left alone.
     */
    @Transactional
    public void release(Long userId, UUID purchaseId) {
        repo.findByUserIdForUpdate(userId)
            .filter(d -> d.getConsumedAt() == null && purchaseId.equals(d.getReservedPurchaseId()))
            .ifPresent(d -> d.setReservedPurchaseId(null));
    }

    /**
     * Marks the discount as used by {@code purchaseId} once its payment is approved. Idempotent: a
     * discount that is already consumed is never overwritten. Consumes even if the discount expired
     * or was released meanwhile, because the customer already paid the discounted price.
     */
    @Transactional
    public void consume(Long userId, UUID purchaseId) {
        repo.findByUserIdForUpdate(userId)
            .filter(d -> d.getConsumedAt() == null)
            .ifPresent(d -> {
                d.setConsumedAt(clock.instant());
                d.setConsumedPurchaseId(purchaseId);
                d.setReservedPurchaseId(null);
            });
    }

    static boolean isUsable(AccountDiscount d, Instant now) {
        return d.getConsumedAt() == null
            && d.getReservedPurchaseId() == null
            && now.isBefore(d.getExpiresAt());
    }
}
