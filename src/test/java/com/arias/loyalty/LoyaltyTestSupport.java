package com.arias.loyalty;

import com.arias.catalog.desserts.Dessert;
import com.arias.catalog.desserts.DessertRepository;
import com.arias.orders.Order;
import com.arias.orders.OrderEstado;
import com.arias.orders.OrderRepository;
import com.arias.users.Role;
import com.arias.users.User;
import com.arias.users.UserRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** Fixtures shared by the loyalty tests. */
public final class LoyaltyTestSupport {

    public static final ZoneId ZONE = ZoneId.of("America/Argentina/Buenos_Aires");

    private LoyaltyTestSupport() {
    }

    public static User newUser(UserRepository repo) {
        return repo.save(User.builder()
            .email("loyalty-" + System.nanoTime() + "@test.arias.com")
            .passwordHash("x")
            .role(Role.EMPLOYEE)
            .active(true)
            .build());
    }

    /** A B2C order with {@code lunches} lunches in the given state; {@code deliveredAt} may be null. */
    public static Order order(OrderRepository repo, User user, int lunches, OrderEstado estado, Instant deliveredAt) {
        Instant pickupAt = deliveredAt != null ? deliveredAt : Instant.now();
        return repo.saveAndFlush(Order.builder()
            .user(user)
            .fecha(LocalDate.ofInstant(pickupAt, ZONE))
            .pickupAt(pickupAt)
            .estado(estado)
            .creditTotal(lunches)
            .deliveredAt(deliveredAt)
            .build());
    }

    public static Order delivered(OrderRepository repo, User user, int lunches, Instant deliveredAt) {
        return order(repo, user, lunches, OrderEstado.ENTREGADO, deliveredAt);
    }

    /** An instant given as a restaurant-zone local date-time, e.g. {@code 2026-11-02T13:00}. */
    public static Instant at(String isoLocalDateTime) {
        return LocalDateTime.parse(isoLocalDateTime).atZone(ZONE).toInstant();
    }

    public static Dessert dessert(DessertRepository repo, String nombre, int stock) {
        return repo.saveAndFlush(Dessert.builder().nombre(nombre).stockDiarioDefault(stock).stockActual(stock)
            .enabled(true).build());
    }

    /** An unused DESSERT reward earned at {@code earnedAt} that expires at {@code expiresAt}, in its own cycle. */
    public static LoyaltyReward dessertReward(LoyaltyCycleRepository cycleRepo, LoyaltyRewardRepository rewardRepo,
                                              User user, Instant earnedAt, Instant expiresAt) {
        LoyaltyCycle cycle = cycleRepo.saveAndFlush(LoyaltyCycle.builder().user(user)
            .startedAt(earnedAt.minusSeconds(3600)).endsAt(earnedAt.plusSeconds(60L * 86400)).build());
        return rewardRepo.saveAndFlush(LoyaltyReward.builder().user(user).cycle(cycle).milestone(6)
            .kind(LoyaltyRewardKind.DESSERT).earnedAt(earnedAt).expiresAt(expiresAt).build());
    }
}
