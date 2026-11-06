package com.arias.loyalty;

import com.arias.credits.discount.AccountDiscount;
import com.arias.credits.discount.AccountDiscountRepository;
import com.arias.orders.Order;
import com.arias.orders.OrderEstado;
import com.arias.orders.OrderKitchenService;
import com.arias.orders.OrderRepository;
import com.arias.users.User;
import com.arias.users.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static com.arias.loyalty.LoyaltyTestSupport.at;
import static com.arias.loyalty.LoyaltyTestSupport.delivered;
import static com.arias.loyalty.LoyaltyTestSupport.newUser;
import static org.assertj.core.api.Assertions.assertThat;

/** Cycle tracking and milestone grants driven by delivered B2C orders. */
@SpringBootTest(properties = "arias.loyalty.enabled=true")
@Transactional
class LoyaltyServiceTest {

    @Autowired private LoyaltyService service;
    @Autowired private LoyaltyCycleRepository cycleRepo;
    @Autowired private LoyaltyRewardRepository rewardRepo;
    @Autowired private OrderRepository orderRepo;
    @Autowired private UserRepository userRepo;
    @Autowired private AccountDiscountRepository discountRepo;
    @Autowired private OrderKitchenService kitchen;

    private List<LoyaltyReward> rewards(User user) {
        return rewardRepo.findByUserIdOrderByMilestoneAsc(user.getId());
    }

    @Test
    @DisplayName("the first delivery starts a cycle of two calendar months and counts 1 lunch")
    void firstDeliveryCreatesCycle() {
        User user = newUser(userRepo);
        Order o = delivered(orderRepo, user, 1, at("2026-11-02T13:00"));

        service.onDelivered(o);

        List<LoyaltyCycle> cycles = cycleRepo.findByUserIdOrderByStartedAtAsc(user.getId());
        assertThat(cycles).singleElement().satisfies(c -> {
            assertThat(c.getStartedAt()).isEqualTo(at("2026-11-02T13:00"));
            assertThat(c.getEndsAt()).isEqualTo(at("2027-01-02T13:00"));
        });
        assertThat(service.lunchesIn(cycles.get(0))).isEqualTo(1);
        assertThat(rewards(user)).isEmpty();
    }

    @Test
    @DisplayName("the 6th lunch grants a dessert that expires 7 days later")
    void sixthLunchGrantsDessert() {
        User user = newUser(userRepo);
        for (int i = 0; i < 5; i++) {
            service.onDelivered(delivered(orderRepo, user, 1, at("2026-11-02T13:00").plus(Duration.ofDays(i))));
        }
        assertThat(rewards(user)).isEmpty();

        Instant sixth = at("2026-11-07T13:00");
        service.onDelivered(delivered(orderRepo, user, 1, sixth));

        assertThat(rewards(user)).singleElement().satisfies(r -> {
            assertThat(r.getMilestone()).isEqualTo(6);
            assertThat(r.getKind()).isEqualTo(LoyaltyRewardKind.DESSERT);
            assertThat(r.getEarnedAt()).isEqualTo(sixth);
            assertThat(r.getExpiresAt()).isEqualTo(sixth.plus(Duration.ofDays(7)));
            assertThat(r.getUsedAt()).isNull();
        });
    }

    @Test
    @DisplayName("an order counts as many lunches as its credit total")
    void multiLunchOrderCountsCreditTotal() {
        User user = newUser(userRepo);
        service.onDelivered(delivered(orderRepo, user, 4, at("2026-11-02T13:00")));
        assertThat(rewards(user)).isEmpty();

        service.onDelivered(delivered(orderRepo, user, 3, at("2026-11-03T13:00")));

        assertThat(rewards(user)).singleElement().satisfies(r -> assertThat(r.getMilestone()).isEqualTo(6));
    }

    @Test
    @DisplayName("undoing a delivery and delivering again never grants twice, and the reward is not revoked")
    void undoAndRedeliverDoesNotDoubleGrant() {
        User user = newUser(userRepo);
        service.onDelivered(delivered(orderRepo, user, 5, at("2026-11-02T13:00")));
        Order sixth = delivered(orderRepo, user, 1, at("2026-11-03T13:00"));
        service.onDelivered(sixth);
        assertThat(rewards(user)).hasSize(1);

        sixth.setEstado(OrderEstado.COMANDADO);
        sixth.setDeliveredAt(null);
        orderRepo.saveAndFlush(sixth);
        assertThat(service.lunchesIn(cycleRepo.findByUserIdOrderByStartedAtAsc(user.getId()).get(0))).isEqualTo(5);
        assertThat(rewards(user)).hasSize(1);

        sixth.setEstado(OrderEstado.ENTREGADO);
        sixth.setDeliveredAt(at("2026-11-04T13:00"));
        orderRepo.saveAndFlush(sixth);
        service.onDelivered(sixth);

        assertThat(rewards(user)).hasSize(1);
    }

    @Test
    @DisplayName("the cycle end is exclusive: a delivery exactly at the end starts a new cycle with 1 lunch")
    void cycleEndIsExclusive() {
        User user = newUser(userRepo);
        service.onDelivered(delivered(orderRepo, user, 1, at("2026-11-02T13:00")));
        Instant end = at("2027-01-02T13:00");

        service.onDelivered(delivered(orderRepo, user, 1, end));

        List<LoyaltyCycle> cycles = cycleRepo.findByUserIdOrderByStartedAtAsc(user.getId());
        assertThat(cycles).hasSize(2);
        assertThat(cycles.get(1).getStartedAt()).isEqualTo(end);
        assertThat(service.lunchesIn(cycles.get(0))).isEqualTo(1);
        assertThat(service.lunchesIn(cycles.get(1))).isEqualTo(1);
    }

    @Test
    @DisplayName("a delivery one second before the end stays in the cycle")
    void deliveryJustBeforeEndStaysInCycle() {
        User user = newUser(userRepo);
        service.onDelivered(delivered(orderRepo, user, 1, at("2026-11-02T13:00")));

        service.onDelivered(delivered(orderRepo, user, 1, at("2027-01-02T13:00").minusSeconds(1)));

        assertThat(cycleRepo.findByUserIdOrderByStartedAtAsc(user.getId())).hasSize(1);
    }

    @Test
    @DisplayName("history rebuild: cycles come from past deliveries and only the current one grants")
    void historyRebuildGrantsOnlyCurrentCycle() {
        User user = newUser(userRepo);
        // Cycle 1: 2026-09-01 13:00 .. 2026-11-01 13:00 (7 lunches: would have earned a dessert).
        delivered(orderRepo, user, 7, at("2026-09-01T13:00"));
        // Cycle 2 starts at the first delivery at/after the end of cycle 1: 3 lunches so far.
        delivered(orderRepo, user, 1, at("2026-11-03T13:00"));
        Order last = delivered(orderRepo, user, 2, at("2026-11-04T13:00"));

        service.onDelivered(last);

        List<LoyaltyCycle> cycles = cycleRepo.findByUserIdOrderByStartedAtAsc(user.getId());
        assertThat(cycles).hasSize(2);
        assertThat(cycles.get(0).getStartedAt()).isEqualTo(at("2026-09-01T13:00"));
        assertThat(cycles.get(1).getStartedAt()).isEqualTo(at("2026-11-03T13:00"));
        assertThat(service.lunchesIn(cycles.get(1))).isEqualTo(3);
        assertThat(rewards(user)).isEmpty();
    }

    @Test
    @DisplayName("history rebuild grants the milestones the current cycle already reached")
    void historyRebuildGrantsReachedMilestones() {
        User user = newUser(userRepo);
        delivered(orderRepo, user, 4, at("2026-11-02T13:00"));
        Order last = delivered(orderRepo, user, 3, at("2026-11-04T13:00"));

        service.onDelivered(last);

        assertThat(rewards(user)).singleElement().satisfies(r -> assertThat(r.getMilestone()).isEqualTo(6));
    }

    @Test
    @DisplayName("the 24th lunch grants a 10% LOYALTY discount; the 18th grants nothing yet")
    void twentyFourthLunchGrantsDiscount() {
        User user = newUser(userRepo);
        service.onDelivered(delivered(orderRepo, user, 18, at("2026-11-02T13:00")));
        assertThat(rewards(user)).extracting(LoyaltyReward::getMilestone).containsExactly(6);

        Instant at24 = at("2026-11-05T13:00");
        service.onDelivered(delivered(orderRepo, user, 6, at24));

        assertThat(rewards(user)).extracting(LoyaltyReward::getKind)
            .containsExactly(LoyaltyRewardKind.DESSERT, LoyaltyRewardKind.DISCOUNT);
        AccountDiscount d = discountRepo.findByUserId(user.getId()).orElseThrow();
        assertThat(d.getPercent()).isEqualTo(10);
        assertThat(d.getSource()).isEqualTo("LOYALTY");
        assertThat(d.getExpiresAt()).isEqualTo(at24.plus(Duration.ofDays(7)));
    }

    @Test
    @DisplayName("view() grants a reached milestone that was never granted (self-heal of a missed accrual)")
    void viewSelfHeals() {
        User user = newUser(userRepo);
        Instant start = Instant.now().minus(Duration.ofDays(3));
        delivered(orderRepo, user, 7, start);
        cycleRepo.saveAndFlush(LoyaltyCycle.builder().user(user).startedAt(start)
            .endsAt(start.plus(Duration.ofDays(60))).build());
        assertThat(rewards(user)).isEmpty();

        service.view(user.getId());

        assertThat(rewards(user)).singleElement().satisfies(r -> assertThat(r.getMilestone()).isEqualTo(6));
    }
}
