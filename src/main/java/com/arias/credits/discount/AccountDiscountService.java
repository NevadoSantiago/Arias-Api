package com.arias.credits.discount;

import com.arias.loyalty.LoyaltyRewardRepository;
import com.arias.loyalty.LoyaltyRewardKind;
import com.arias.payments.CreditPurchaseRepository;
import com.arias.payments.CreditPurchaseStatus;
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

    /** Source label of a discount backed by the loyalty line (its reward is marked used when consumed). */
    private static final String LOYALTY_SOURCE = "LOYALTY";

    /** A discounted Mercado Pago preference is payable only this long. */
    public static final Duration DISCOUNT_HOLD = Duration.ofMinutes(30);
    /** Extra time before a held discount is considered free again. */
    private static final Duration HOLD_MARGIN = Duration.ofMinutes(5);

    private final AccountDiscountRepository repo;
    private final CreditPurchaseRepository purchaseRepo;
    private final LoyaltyRewardRepository loyaltyRewardRepo;
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

    /**
     * Grants {@code percent}% under the "one pending discount" rule, updating the user's single row in
     * place (the row is locked while deciding): no row inserts; a consumed or expired row is overwritten;
     * an active row with a higher percent stays; an equal percent keeps the later expiry; a lower percent
     * is raised and renewed (a reservation by a pending purchase stays, that purchase keeps its own price).
     */
    @Transactional
    public void grantReplacing(User user, int percent, String source, Instant now) {
        Instant expiresAt = now.plus(VALIDITY);
        Optional<AccountDiscount> locked = repo.findByUserIdForUpdate(user.getId());
        if (locked.isEmpty()) {
            if (repo.insertIfAbsent(user.getId(), percent, now, expiresAt, source) == 1) {
                return;
            }
            locked = repo.findByUserIdForUpdate(user.getId());
        }
        locked.ifPresent(d -> {
            if (d.getConsumedAt() != null || !now.isBefore(d.getExpiresAt())) {
                d.setPercent(percent);
                d.setSource(source);
                d.setGrantedAt(now);
                d.setExpiresAt(expiresAt);
                d.setReservedPurchaseId(null);
                d.setConsumedAt(null);
                d.setConsumedPurchaseId(null);
            } else if (percent == d.getPercent()) {
                d.setSource(source);
                if (expiresAt.isAfter(d.getExpiresAt())) {
                    d.setExpiresAt(expiresAt);
                }
            } else if (percent > d.getPercent()) {
                d.setGrantedAt(now);
                d.setPercent(percent);
                d.setSource(source);
                d.setExpiresAt(expiresAt);
            }
        });
    }

    /**
     * The discount a new purchase would get at {@code now} (see {@link #isUsable}); hidden
     * during the hold of a PENDING purchase so the app never shows a price the server will not charge.
     */
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
     * Locks the user's discount row (FOR UPDATE) and returns it if usable at {@code now}. A reservation
     * held by another (necessarily still PENDING) purchase is taken over by {@link #reserve}. Joins the
     * caller's transaction so the reservation is atomic with the purchase insert.
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
    public Optional<UUID> consume(Long userId, UUID purchaseId) {
        return repo.findByUserIdForUpdate(userId).flatMap(d -> {
            if (d.getConsumedAt() != null) {
                // Already used: report a DIFFERENT consumer so the caller can flag the double use.
                return Optional.ofNullable(d.getConsumedPurchaseId()).filter(id -> !id.equals(purchaseId));
            }
            d.setConsumedAt(clock.instant());
            d.setConsumedPurchaseId(purchaseId);
            if (LOYALTY_SOURCE.equals(d.getSource())) {
                loyaltyRewardRepo.findFirstByUserIdAndKindAndUsedAtIsNullOrderByEarnedAtDescIdDesc(
                    userId, LoyaltyRewardKind.DISCOUNT).ifPresent(r -> r.setUsedAt(d.getConsumedAt()));
            }
            d.setReservedPurchaseId(null);
            return Optional.empty();
        });
    }

    /**
     * Usable by a NEW purchase: unexpired, unconsumed and not held by a PENDING purchase still inside
     * its payable window ({@link #DISCOUNT_HOLD} + margin). After that window the held checkout can no
     * longer be paid (its preference expires), so a new purchase may take the reservation over.
     */
    private boolean isUsable(AccountDiscount d, Instant now) {
        if (d.getConsumedAt() != null || !now.isBefore(d.getExpiresAt())) {
            return false;
        }
        Instant heldSince = now.minus(DISCOUNT_HOLD).minus(HOLD_MARGIN);
        return d.getReservedPurchaseId() == null || purchaseRepo.findById(d.getReservedPurchaseId())
            .map(p -> p.getStatus() != CreditPurchaseStatus.PENDING || !p.getCreatedAt().isAfter(heldSince))
            .orElse(true);
    }
}
