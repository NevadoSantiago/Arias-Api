package com.arias.loyalty;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface LoyaltyRewardRepository extends JpaRepository<LoyaltyReward, Long> {

    List<LoyaltyReward> findByUserIdOrderByMilestoneAsc(Long userId);

    List<LoyaltyReward> findByCycleId(Long cycleId);

    boolean existsByCycleIdAndMilestone(Long cycleId, Integer milestone);

    /** Unused desserts still valid at {@code now}, the one expiring first at the head. */
    @Query("""
        SELECT r FROM LoyaltyReward r
        WHERE r.user.id = :userId AND r.kind = com.arias.loyalty.LoyaltyRewardKind.DESSERT
          AND r.usedAt IS NULL AND r.expiresAt > :now
        ORDER BY r.expiresAt ASC, r.id ASC
        """)
    List<LoyaltyReward> findPendingDesserts(@Param("userId") Long userId, @Param("now") Instant now);

    default Optional<LoyaltyReward> findPendingDessert(Long userId, Instant now) {
        return findPendingDesserts(userId, now).stream().findFirst();
    }
}
