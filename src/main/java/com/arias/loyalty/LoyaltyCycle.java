package com.arias.loyalty;

import com.arias.users.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** A personal 2-month cycle; {@code endsAt} is exclusive. The lunch count is derived from orders. */
@Entity
@Table(name = "loyalty_cycle")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoyaltyCycle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;
}
