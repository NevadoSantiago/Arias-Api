package com.arias.credits.discount;

import com.arias.users.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * The one-time account discount of a user (V41). A user never has more than one row
 * (UNIQUE {@code user_id}), so it can be granted only once in a lifetime.
 *
 * <p>{@code reservedPurchaseId} is non-null while a live PENDING purchase holds it;
 * {@code consumedAt} is set when that purchase is approved.
 */
@Entity
@Table(name = "account_discount")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AccountDiscount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private Integer percent;

    @Column(name = "granted_at", nullable = false)
    private Instant grantedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "reserved_purchase_id")
    private UUID reservedPurchaseId;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "consumed_purchase_id")
    private UUID consumedPurchaseId;

    @Column(nullable = false, length = 50)
    private String source;
}
