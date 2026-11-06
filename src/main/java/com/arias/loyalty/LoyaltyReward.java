package com.arias.loyalty;

import com.arias.users.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** A prize earned in a cycle; unique per (cycle, milestone). */
@Entity
@Table(name = "loyalty_reward")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoyaltyReward {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cycle_id", nullable = false)
    private LoyaltyCycle cycle;

    @Column(nullable = false)
    private Integer milestone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LoyaltyRewardKind kind;

    @Column(name = "earned_at", nullable = false)
    private Instant earnedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "used_order_id")
    private Long usedOrderId;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;
}
