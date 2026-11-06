package com.arias.loyalty;

import com.arias.credits.discount.AccountDiscount;
import com.arias.credits.discount.AccountDiscountService;
import com.arias.orders.Order;
import com.arias.orders.OrderRepository;
import com.arias.users.User;
import com.arias.users.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Personal 2-month cycles and the prizes earned in them. The lunch count of a cycle is always
 * derived from the user's delivered B2C orders, so undoing a delivery lowers it; prizes already
 * granted are never revoked and, being unique per (cycle, milestone), never granted twice.
 */
@Service
@RequiredArgsConstructor
public class LoyaltyService {

    static final ZoneId ZONE = ZoneId.of("America/Argentina/Buenos_Aires");
    static final int CYCLE_MONTHS = 2;
    static final Duration REWARD_VALIDITY = Duration.ofDays(7);
    static final String DISCOUNT_SOURCE = "LOYALTY";

    private final LoyaltyProperties props;
    private final LoyaltyCycleRepository cycleRepo;
    private final LoyaltyRewardRepository rewardRepo;
    private final OrderRepository orderRepo;
    private final UserRepository userRepo;
    private final AccountDiscountService discountService;
    private final LoyaltyLock lock;
    private final Clock clock;

    public boolean isEnabled() {
        return props.enabled();
    }

    /**
     * Hook of the kitchen "delivered" transition, run in its transaction: opens the cycle that
     * contains the delivery (rebuilding the history first when the user has none) and grants the
     * milestones reached. B2B orders and a disabled feature are ignored.
     */
    @Transactional
    public void onDelivered(Order order) {
        if (!props.enabled() || order.getCompany() != null || order.getDeliveredAt() == null) {
            return;
        }
        User user = order.getUser();
        lock.lock(user.getId());
        Instant at = order.getDeliveredAt();
        rebuildHistoryIfMissing(user);
        LoyaltyCycle cycle = cycleRepo.findContaining(user.getId(), at).orElseGet(() -> openCycle(user, at));
        grantReached(user, cycle, at);
    }

    /** The lunches picked up inside the cycle: the sum of the credit totals of its delivered B2C orders. */
    @Transactional(readOnly = true)
    public int lunchesIn(LoyaltyCycle cycle) {
        return orderRepo.sumLoyaltyLunches(cycle.getUser().getId(), cycle.getStartedAt(), cycle.getEndsAt());
    }

    /** The progress shown to a B2C customer; lazily rebuilds the history of customers that predate the feature. */
    @Transactional
    public LoyaltyDto view(Long userId) {
        Instant now = clock.instant();
        lock.lock(userId);
        User user = userRepo.getReferenceById(userId);
        rebuildHistoryIfMissing(user);
        Optional<LoyaltyCycle> current = cycleRepo.findContaining(userId, now);
        current.ifPresent(c -> grantReached(user, c, now));
        List<LoyaltyCycle> cycles = cycleRepo.findByUserIdOrderByStartedAtAsc(userId);
        boolean waiting = current.isEmpty() && !cycles.isEmpty();
        Optional<LoyaltyCycle> shown = current.or(() -> cycles.isEmpty()
            ? Optional.empty() : Optional.of(cycles.get(cycles.size() - 1)));

        Map<Integer, LoyaltyReward> byMilestone = shown
            .map(c -> rewardRepo.findByCycleId(c.getId()).stream()
                .collect(Collectors.toMap(LoyaltyReward::getMilestone, Function.identity())))
            .orElse(Map.of());
        List<LoyaltyDto.MilestoneDto> milestones = LoyaltyMilestone.ALL.stream()
            .map(m -> milestoneDto(m, byMilestone.get(m.lunches()), now))
            .toList();
        LoyaltyDto.CycleDto cycle = current
            .map(c -> new LoyaltyDto.CycleDto(c.getStartedAt(), c.getEndsAt(), lunchesIn(c)))
            .orElse(null);
        LoyaltyDto.PendingDessertDto pending = rewardRepo.findPendingDessert(userId, now)
            .map(r -> new LoyaltyDto.PendingDessertDto(r.getId(), r.getExpiresAt()))
            .orElse(null);
        return new LoyaltyDto(true, cycle, waiting, milestones, pending);
    }

    private LoyaltyDto.MilestoneDto milestoneDto(LoyaltyMilestone m, LoyaltyReward reward, Instant now) {
        Integer percent = m.kind() == LoyaltyRewardKind.DISCOUNT ? LoyaltyMilestone.DISCOUNT_PERCENT : null;
        LoyaltyDto.MilestoneStatus status = reward == null ? LoyaltyDto.MilestoneStatus.LOCKED
            : reward.getUsedAt() != null ? LoyaltyDto.MilestoneStatus.USED
            : reward.getExpiresAt() != null && !now.isBefore(reward.getExpiresAt()) ? LoyaltyDto.MilestoneStatus.EXPIRED
            : LoyaltyDto.MilestoneStatus.EARNED;
        return new LoyaltyDto.MilestoneDto(m.lunches(), m.kind(), percent, status,
            reward == null ? null : reward.getExpiresAt());
    }

    /**
     * Builds the cycles of a user that has none from all their delivered B2C orders: the first
     * delivery starts a cycle and the first delivery at or after its end starts the next. Past
     * cycles grant nothing.
     *
     * @return whether cycles were created
     */
    private boolean rebuildHistoryIfMissing(User user) {
        if (cycleRepo.existsByUserId(user.getId())) {
            return false;
        }
        Instant end = null;
        boolean created = false;
        for (Order o : orderRepo.findLoyaltyDeliveries(user.getId())) {
            Instant at = o.getDeliveredAt();
            if (end == null || !at.isBefore(end)) {
                end = openCycle(user, at).getEndsAt();
                created = true;
            }
        }
        return created;
    }

    private LoyaltyCycle openCycle(User user, Instant startedAt) {
        Instant endsAt = startedAt.atZone(ZONE).plusMonths(CYCLE_MONTHS).toInstant();
        return cycleRepo.saveAndFlush(LoyaltyCycle.builder().user(user).startedAt(startedAt).endsAt(endsAt).build());
    }

    private void grantReached(User user, LoyaltyCycle cycle, Instant at) {
        int lunches = lunchesIn(cycle);
        for (LoyaltyMilestone m : LoyaltyMilestone.ALL) {
            if (lunches >= m.lunches() && !rewardRepo.existsByCycleIdAndMilestone(cycle.getId(), m.lunches())) {
                grant(user, cycle, m, at);
            }
        }
    }

    private void grant(User user, LoyaltyCycle cycle, LoyaltyMilestone m, Instant at) {
        switch (m.kind()) {
            case DESSERT -> saveReward(user, cycle, m, at);
            case DISCOUNT -> {
                saveReward(user, cycle, m, at);
                discountService.grantReplacing(user, LoyaltyMilestone.DISCOUNT_PERCENT, DISCOUNT_SOURCE, at);
            }
            case LUNCH -> {
                // Task B4: save the reward row (expires with the wallet balance) and credit the wallet
                // with the new LOYALTY_GRANT movement. Until then the milestone stays unearned.
            }
        }
    }

    private void saveReward(User user, LoyaltyCycle cycle, LoyaltyMilestone m, Instant at) {
        rewardRepo.saveAndFlush(LoyaltyReward.builder()
            .user(user).cycle(cycle).milestone(m.lunches()).kind(m.kind())
            .earnedAt(at).expiresAt(at.plus(REWARD_VALIDITY)).build());
    }
}
