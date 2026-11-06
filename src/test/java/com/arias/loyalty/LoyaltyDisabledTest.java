package com.arias.loyalty;

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

import java.util.List;

import static com.arias.loyalty.LoyaltyTestSupport.newUser;
import static org.assertj.core.api.Assertions.assertThat;

/** With the switch off nothing accrues. */
@SpringBootTest(properties = "arias.loyalty.enabled=false")
@Transactional
class LoyaltyDisabledTest {

    @Autowired private OrderKitchenService kitchen;
    @Autowired private OrderRepository orderRepo;
    @Autowired private UserRepository userRepo;
    @Autowired private LoyaltyCycleRepository cycleRepo;
    @Autowired private LoyaltyRewardRepository rewardRepo;

    @Test
    @DisplayName("markEntregado() creates no cycle and grants nothing while disabled")
    void noAccrualWhenDisabled() {
        User user = newUser(userRepo);
        Order o = LoyaltyTestSupport.order(orderRepo, user, 6, OrderEstado.COMANDADO, null);

        kitchen.markEntregado(List.of(o.getId()));

        assertThat(o.getEstado()).isEqualTo(OrderEstado.ENTREGADO);
        assertThat(cycleRepo.findByUserIdOrderByStartedAtAsc(user.getId())).isEmpty();
        assertThat(rewardRepo.findByUserIdOrderByMilestoneAsc(user.getId())).isEmpty();
    }
}
