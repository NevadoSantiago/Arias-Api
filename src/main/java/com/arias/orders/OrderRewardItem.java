package com.arias.orders;

import com.arias.catalog.desserts.Dessert;
import com.arias.loyalty.LoyaltyReward;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * A loyalty reward line of an order (the gift dessert). It costs no credits and is never part of
 * {@code order_item}; {@code dessertNombre} is a snapshot, like the dish name of an order item.
 */
@Entity
@Table(name = "order_reward_item")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderRewardItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reward_id", nullable = false)
    private LoyaltyReward reward;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dessert_id", nullable = false)
    private Dessert dessert;

    @Column(name = "dessert_nombre", nullable = false, length = 150)
    private String dessertNombre;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;
}
