package com.arias.loyalty;

import com.arias.credits.CreditMovement;
import com.arias.credits.CreditMovementRepository;
import com.arias.credits.CreditWallet;
import com.arias.credits.CreditWalletRepository;
import com.arias.credits.MovementType;
import com.arias.orders.Order;
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

/** The 18th-lunch prize: one free lunch credited to the wallet, once per cycle. */
@SpringBootTest(properties = "arias.loyalty.enabled=true")
@Transactional
class LoyaltyLunchGrantTest {

    @Autowired private LoyaltyService service;
    @Autowired private LoyaltyCycleRepository cycleRepo;
    @Autowired private LoyaltyRewardRepository rewardRepo;
    @Autowired private OrderRepository orderRepo;
    @Autowired private UserRepository userRepo;
    @Autowired private CreditWalletRepository walletRepo;
    @Autowired private CreditMovementRepository movementRepo;

    private List<CreditMovement> grants(User user) {
        return movementRepo.findByUserIdOrderByCreatedAtDesc(user.getId()).stream()
            .filter(m -> m.getType() == MovementType.LOYALTY_GRANT).toList();
    }

    @Test
    @DisplayName("the 18th lunch credits +1 available with a LOYALTY_GRANT movement and creates the wallet with its expiry")
    void eighteenthLunchCreditsNewWallet() {
        User user = newUser(userRepo);
        assertThat(walletRepo.findById(user.getId())).isEmpty();

        service.onDelivered(delivered(orderRepo, user, 18, at("2026-11-02T13:00")));

        CreditWallet wallet = walletRepo.findById(user.getId()).orElseThrow();
        assertThat(wallet.getAvailable()).isEqualTo(1);
        assertThat(wallet.getCommitted()).isZero();
        assertThat(wallet.getExpiresAt()).isNotNull();
        assertThat(grants(user)).singleElement().satisfies(m -> {
            assertThat(m.getDeltaAvailable()).isEqualTo(1);
            assertThat(m.getDeltaCommitted()).isZero();
        });
        assertThat(rewardRepo.findByUserIdOrderByMilestoneAsc(user.getId()))
            .extracting(LoyaltyReward::getMilestone, LoyaltyReward::getKind)
            .contains(org.assertj.core.groups.Tuple.tuple(18, LoyaltyRewardKind.LUNCH));
    }

    @Test
    @DisplayName("an existing wallet is topped up and keeps its own expiry")
    void eighteenthLunchTopsUpExistingWallet() {
        User user = newUser(userRepo);
        Instant expiry = Instant.now().plus(Duration.ofDays(30));
        walletRepo.saveAndFlush(CreditWallet.builder().userId(user.getId()).available(2).committed(1)
            .expiresAt(expiry).build());

        service.onDelivered(delivered(orderRepo, user, 18, at("2026-11-02T13:00")));

        CreditWallet wallet = walletRepo.findById(user.getId()).orElseThrow();
        assertThat(wallet.getAvailable()).isEqualTo(3);
        assertThat(wallet.getCommitted()).isEqualTo(1);
        assertThat(wallet.getExpiresAt()).isEqualTo(expiry);
        assertThat(grants(user)).hasSize(1);
    }

    @Test
    @DisplayName("an empty wallet whose expiry already passed gets a fresh expiry, so the gift is not swept at once")
    void eighteenthLunchRenewsStaleExpiryOfEmptyWallet() {
        User user = newUser(userRepo);
        walletRepo.saveAndFlush(CreditWallet.builder().userId(user.getId()).available(0).committed(0)
            .expiresAt(Instant.now().minus(Duration.ofDays(6))).build());

        service.onDelivered(delivered(orderRepo, user, 18, at("2026-11-02T13:00")));

        CreditWallet wallet = walletRepo.findById(user.getId()).orElseThrow();
        assertThat(wallet.getAvailable()).isEqualTo(1);
        assertThat(wallet.getExpiresAt()).isAfter(Instant.now().plus(Duration.ofDays(1)));
    }

    @Test
    @DisplayName("below 18 lunches nothing is credited")
    void seventeenLunchesGrantNothing() {
        User user = newUser(userRepo);

        service.onDelivered(delivered(orderRepo, user, 17, at("2026-11-02T13:00")));

        assertThat(grants(user)).isEmpty();
        assertThat(walletRepo.findById(user.getId())).isEmpty();
    }

    @Test
    @DisplayName("repeated deliveries and views never grant the lunch twice in the same cycle")
    void noDoubleGrant() {
        User user = newUser(userRepo);
        Order first = delivered(orderRepo, user, 18, at("2026-11-02T13:00"));
        service.onDelivered(first);
        service.onDelivered(first);
        service.onDelivered(delivered(orderRepo, user, 2, Instant.now().minus(Duration.ofDays(1))));

        assertThat(grants(user)).hasSize(1);
        assertThat(walletRepo.findById(user.getId()).orElseThrow().getAvailable()).isEqualTo(1);
    }

    @Test
    @DisplayName("view() self-heals a missed 18th-lunch grant exactly once")
    void viewSelfHealsOnce() {
        User user = newUser(userRepo);
        Instant start = Instant.now().minus(Duration.ofDays(3));
        delivered(orderRepo, user, 18, start);
        cycleRepo.saveAndFlush(LoyaltyCycle.builder().user(user).startedAt(start)
            .endsAt(start.plus(Duration.ofDays(60))).build());

        var first = service.view(user.getId());
        service.view(user.getId());

        assertThat(grants(user)).hasSize(1);
        assertThat(walletRepo.findById(user.getId()).orElseThrow().getAvailable()).isEqualTo(1);
        assertThat(first.milestones().get(1).status()).isEqualTo(LoyaltyDto.MilestoneStatus.EARNED);
        assertThat(first.milestones().get(1).expiresAt()).isNull();
    }
}
