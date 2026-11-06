package com.arias.loyalty;

import com.arias.credits.discount.AccountDiscount;
import com.arias.credits.discount.AccountDiscountRepository;
import com.arias.credits.discount.AccountDiscountService;
import com.arias.orders.OrderRepository;
import com.arias.payments.CreditPurchase;
import com.arias.payments.CreditPurchaseRepository;
import com.arias.payments.CreditPurchaseStatus;
import com.arias.payments.PurchaseType;
import com.arias.users.User;
import com.arias.users.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static com.arias.loyalty.LoyaltyTestSupport.delivered;
import static com.arias.loyalty.LoyaltyTestSupport.newUser;
import static org.assertj.core.api.Assertions.assertThat;

/** The USED status of the 24-lunch discount follows the loyalty reward, not a heuristic on the discount row. */
@SpringBootTest(properties = "arias.loyalty.enabled=true")
@Transactional
class LoyaltyDiscountUsedTest {

    @Autowired private LoyaltyService service;
    @Autowired private LoyaltyRewardRepository rewardRepo;
    @Autowired private OrderRepository orderRepo;
    @Autowired private UserRepository userRepo;
    @Autowired private AccountDiscountRepository discountRepo;
    @Autowired private AccountDiscountService discountService;
    @Autowired private CreditPurchaseRepository purchaseRepo;


    /** A real purchase row (the discount keeps a foreign key to it). */
    private UUID purchaseOf(User user) {
        var order = LoyaltyTestSupport.order(orderRepo, user, 1, com.arias.orders.OrderEstado.CONFIRMADO, null);
        return purchaseRepo.saveAndFlush(CreditPurchase.builder()
            .user(user).type(PurchaseType.DIRECT).order(order).creditAmount(1).amountCents(100_00L)
            .currency("ARS").status(CreditPurchaseStatus.APPROVED).build()).getId();
    }

    private Instant daysFromNow(int days) {
        return Instant.now().plus(Duration.ofDays(days));
    }

    private void existing(User user, int percent, Instant expiresAt, String source) {
        discountRepo.saveAndFlush(AccountDiscount.builder()
            .user(user).percent(percent).grantedAt(Instant.now().minus(Duration.ofDays(1)))
            .expiresAt(expiresAt).source(source).build());
    }

    private LoyaltyDto.MilestoneStatus discountStatus(User user) {
        return service.view(user.getId()).milestones().get(2).status();
    }

    private User userWith24Lunches() {
        User user = newUser(userRepo);
        service.onDelivered(delivered(orderRepo, user, 24, Instant.now().minus(Duration.ofDays(1))));
        return user;
    }

    @Test
    @DisplayName("a discount created by the milestone is USED once consumed")
    void newRowIsUsedWhenConsumed() {
        User user = userWith24Lunches();
        assertThat(discountStatus(user)).isEqualTo(LoyaltyDto.MilestoneStatus.EARNED);

        discountService.consume(user.getId(), purchaseOf(user));

        assertThat(discountStatus(user)).isEqualTo(LoyaltyDto.MilestoneStatus.USED);
    }

    @Test
    @DisplayName("a lower discount raised by the milestone is USED once consumed")
    void raisedRowIsUsedWhenConsumed() {
        User user = newUser(userRepo);
        existing(user, 5, daysFromNow(3), "REVIEW_CAMPAIGN");
        service.onDelivered(delivered(orderRepo, user, 24, Instant.now().minus(Duration.ofDays(1))));
        assertThat(discountRepo.findByUserId(user.getId()).orElseThrow().getSource()).isEqualTo("LOYALTY");

        discountService.consume(user.getId(), purchaseOf(user));

        assertThat(discountStatus(user)).isEqualTo(LoyaltyDto.MilestoneStatus.USED);
    }

    @Test
    @DisplayName("an equal discount that stays is attributed to the milestone and USED once consumed")
    void equalRowIsUsedWhenConsumed() {
        User user = newUser(userRepo);
        existing(user, 10, daysFromNow(20), "REVIEW_CAMPAIGN");
        service.onDelivered(delivered(orderRepo, user, 24, Instant.now().minus(Duration.ofDays(1))));
        assertThat(discountRepo.findByUserId(user.getId()).orElseThrow().getSource()).isEqualTo("LOYALTY");

        discountService.consume(user.getId(), purchaseOf(user));

        assertThat(discountStatus(user)).isEqualTo(LoyaltyDto.MilestoneStatus.USED);
    }

    @Test
    @DisplayName("a higher discount that stays is not the milestone's: consuming it leaves the reward EARNED")
    void higherRowDoesNotUseTheReward() {
        User user = newUser(userRepo);
        existing(user, 20, daysFromNow(3), "REVIEW_CAMPAIGN");
        service.onDelivered(delivered(orderRepo, user, 24, Instant.now().minus(Duration.ofDays(1))));

        discountService.consume(user.getId(), purchaseOf(user));

        assertThat(discountStatus(user)).isEqualTo(LoyaltyDto.MilestoneStatus.EARNED);
    }

    @Test
    @DisplayName("consuming twice (idempotent) keeps a single used reward")
    void consumeIsIdempotent() {
        User user = userWith24Lunches();
        UUID purchase = purchaseOf(user);

        discountService.consume(user.getId(), purchase);
        discountService.consume(user.getId(), purchase);

        assertThat(rewardRepo.findByUserIdOrderByMilestoneAsc(user.getId()))
            .filteredOn(r -> r.getUsedAt() != null).singleElement()
            .satisfies(r -> assertThat(r.getKind()).isEqualTo(LoyaltyRewardKind.DISCOUNT));
    }
}
