package com.arias.loyalty;

import com.arias.orders.Order;
import com.arias.orders.OrderEstado;
import com.arias.orders.OrderKitchenService;
import com.arias.orders.OrderRepository;
import com.arias.users.User;
import com.arias.users.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.arias.loyalty.LoyaltyTestSupport.delivered;
import static com.arias.loyalty.LoyaltyTestSupport.newUser;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Committed-transaction scenarios (no test transaction): concurrent loyalty calls of one user and
 * the post-commit accrual that must never take the kitchen delivery down with it.
 */
@SpringBootTest(properties = "arias.loyalty.enabled=true")
class LoyaltyRobustnessTest {

    @Autowired private LoyaltyService service;
    @Autowired private OrderKitchenService kitchen;
    @Autowired private LoyaltyCycleRepository cycleRepo;
    @Autowired private LoyaltyRewardRepository rewardRepo;
    @Autowired private OrderRepository orderRepo;
    @Autowired private UserRepository userRepo;
    @MockitoSpyBean private LoyaltyService spiedService;

    private final List<User> created = new ArrayList<>();

    private User user() {
        User u = newUser(userRepo);
        created.add(u);
        return u;
    }

    @AfterEach
    void cleanUp() {
        Mockito.reset(spiedService);
        for (User u : created) {
            rewardRepo.deleteAll(rewardRepo.findByUserIdOrderByMilestoneAsc(u.getId()));
            cycleRepo.deleteAll(cycleRepo.findByUserIdOrderByStartedAtAsc(u.getId()));
            orderRepo.deleteAll(orderRepo.findAll().stream()
                .filter(o -> o.getUser() != null && o.getUser().getId().equals(u.getId())).toList());
            userRepo.deleteById(u.getId());
        }
        created.clear();
    }

    @Test
    @DisplayName("concurrent views of a customer with history build exactly one cycle and one reward per milestone")
    void concurrentViewsDoNotDuplicate() throws Exception {
        User u = user();
        delivered(orderRepo, u, 7, Instant.now().minus(Duration.ofDays(2)));
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                go.await();
                return service.view(u.getId());
            }));
        }
        go.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        assertThat(cycleRepo.findByUserIdOrderByStartedAtAsc(u.getId())).hasSize(1);
        assertThat(rewardRepo.findByUserIdOrderByMilestoneAsc(u.getId())).hasSize(1);
    }

    @Test
    @DisplayName("granting is idempotent: calling the delivery hook twice grants the milestone once")
    void grantTwiceIsHarmless() {
        User u = user();
        Order o = delivered(orderRepo, u, 6, Instant.now().minus(Duration.ofDays(1)));

        service.onDelivered(o);
        service.onDelivered(o);

        assertThat(cycleRepo.findByUserIdOrderByStartedAtAsc(u.getId())).hasSize(1);
        assertThat(rewardRepo.findByUserIdOrderByMilestoneAsc(u.getId())).hasSize(1);
    }

    @Test
    @DisplayName("markEntregado accrues loyalty after the commit")
    void accruesAfterCommit() {
        User u = user();
        Order o = LoyaltyTestSupport.order(orderRepo, u, 6, OrderEstado.COMANDADO, null);

        kitchen.markEntregado(List.of(o.getId()));

        assertThat(rewardRepo.findByUserIdOrderByMilestoneAsc(u.getId()))
            .singleElement().satisfies(r -> assertThat(r.getMilestone()).isEqualTo(6));
    }

    @Test
    @DisplayName("a loyalty failure never rolls back the kitchen delivery")
    void loyaltyFailureDoesNotBlockDelivery() {
        User u = user();
        Order o = LoyaltyTestSupport.order(orderRepo, u, 6, OrderEstado.COMANDADO, null);
        Mockito.doThrow(new IllegalStateException("boom")).when(spiedService).onDelivered(Mockito.any());

        kitchen.markEntregado(List.of(o.getId()));

        Order reloaded = orderRepo.findById(o.getId()).orElseThrow();
        assertThat(reloaded.getEstado()).isEqualTo(OrderEstado.ENTREGADO);
        assertThat(reloaded.getDeliveredAt()).isNotNull();
        assertThat(rewardRepo.findByUserIdOrderByMilestoneAsc(u.getId())).isEmpty();
    }
}
