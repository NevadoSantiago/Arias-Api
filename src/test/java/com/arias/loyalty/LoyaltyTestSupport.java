package com.arias.loyalty;

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
final class LoyaltyTestSupport {

    static final ZoneId ZONE = ZoneId.of("America/Argentina/Buenos_Aires");

    private LoyaltyTestSupport() {
    }

    static User newUser(UserRepository repo) {
        return repo.save(User.builder()
            .email("loyalty-" + System.nanoTime() + "@test.arias.com")
            .passwordHash("x")
            .role(Role.EMPLOYEE)
            .active(true)
            .build());
    }

    /** A B2C order with {@code lunches} lunches in the given state; {@code deliveredAt} may be null. */
    static Order order(OrderRepository repo, User user, int lunches, OrderEstado estado, Instant deliveredAt) {
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

    static Order delivered(OrderRepository repo, User user, int lunches, Instant deliveredAt) {
        return order(repo, user, lunches, OrderEstado.ENTREGADO, deliveredAt);
    }

    /** An instant given as a restaurant-zone local date-time, e.g. {@code 2026-11-02T13:00}. */
    static Instant at(String isoLocalDateTime) {
        return LocalDateTime.parse(isoLocalDateTime).atZone(ZONE).toInstant();
    }
}
