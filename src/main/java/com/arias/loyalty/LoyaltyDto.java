package com.arias.loyalty;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * Response of {@code GET /api/v1/loyalty} when the feature applies to the caller.
 *
 * @param cycle   the running cycle, or null when none is running
 * @param waiting true when the last cycle already ended and the next one starts with the next lunch picked up
 */
public record LoyaltyDto(boolean enabled, CycleDto cycle, boolean waiting, List<MilestoneDto> milestones,
                         PendingDessertDto pendingDessert) {

    public record CycleDto(Instant startedAt, Instant endsAt, int lunches) {
    }

    public enum MilestoneStatus { LOCKED, EARNED, USED, EXPIRED }

    /** {@code percent} is present only for the discount prize. */
    public record MilestoneDto(int lunches, LoyaltyRewardKind kind,
                               @JsonInclude(JsonInclude.Include.NON_NULL) Integer percent,
                               MilestoneStatus status, Instant expiresAt) {
    }

    public record PendingDessertDto(Long rewardId, Instant expiresAt) {
    }
}
