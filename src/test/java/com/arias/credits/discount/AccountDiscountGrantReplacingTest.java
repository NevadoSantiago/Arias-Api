package com.arias.credits.discount;

import com.arias.users.Role;
import com.arias.users.User;
import com.arias.users.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link AccountDiscountService#grantReplacing}: one pending discount, the larger one wins. */
@SpringBootTest
@Transactional
class AccountDiscountGrantReplacingTest {

    private static final Instant NOW = Instant.parse("2026-11-10T15:00:00Z");
    private static final Duration SEVEN_DAYS = Duration.ofDays(7);

    @Autowired private AccountDiscountService service;
    @Autowired private AccountDiscountRepository repo;
    @Autowired private UserRepository userRepo;

    private User newUser() {
        return userRepo.save(User.builder()
            .email("replace-" + System.nanoTime() + "@test.arias.com")
            .passwordHash("x").role(Role.EMPLOYEE).active(true).build());
    }

    private AccountDiscount existing(User user, int percent, Instant expiresAt, String source) {
        return repo.saveAndFlush(AccountDiscount.builder()
            .user(user).percent(percent).grantedAt(NOW.minus(Duration.ofDays(1)))
            .expiresAt(expiresAt).source(source).build());
    }

    private AccountDiscount reload(User user) {
        repo.flush();
        return repo.findByUserId(user.getId()).orElseThrow();
    }

    @Test
    @DisplayName("no row: inserts the discount for 7 days")
    void insertsWhenNone() {
        User user = newUser();

        service.grantReplacing(user, 10, "LOYALTY", NOW);

        AccountDiscount d = reload(user);
        assertThat(d.getPercent()).isEqualTo(10);
        assertThat(d.getSource()).isEqualTo("LOYALTY");
        assertThat(d.getGrantedAt()).isEqualTo(NOW);
        assertThat(d.getExpiresAt()).isEqualTo(NOW.plus(SEVEN_DAYS));
    }

    @Test
    @DisplayName("consumed row: is overwritten and its usage cleared")
    void overwritesConsumed() {
        User user = newUser();
        AccountDiscount old = existing(user, 10, NOW.plus(Duration.ofDays(3)), "REVIEW_CAMPAIGN");
        old.setConsumedAt(NOW.minusSeconds(60));
        old.setConsumedPurchaseId(null);
        repo.saveAndFlush(old);

        service.grantReplacing(user, 10, "LOYALTY", NOW);

        AccountDiscount d = reload(user);
        assertThat(d.getConsumedAt()).isNull();
        assertThat(d.getConsumedPurchaseId()).isNull();
        assertThat(d.getReservedPurchaseId()).isNull();
        assertThat(d.getSource()).isEqualTo("LOYALTY");
        assertThat(d.getGrantedAt()).isEqualTo(NOW);
        assertThat(d.getExpiresAt()).isEqualTo(NOW.plus(SEVEN_DAYS));
    }

    @Test
    @DisplayName("expired row: is overwritten even when its percent was higher")
    void overwritesExpired() {
        User user = newUser();
        existing(user, 30, NOW.minusSeconds(1), "REVIEW_CAMPAIGN");

        service.grantReplacing(user, 10, "LOYALTY", NOW);

        AccountDiscount d = reload(user);
        assertThat(d.getPercent()).isEqualTo(10);
        assertThat(d.getSource()).isEqualTo("LOYALTY");
        assertThat(d.getExpiresAt()).isEqualTo(NOW.plus(SEVEN_DAYS));
    }

    @Test
    @DisplayName("active row with a higher percent is kept unchanged")
    void keepsHigher() {
        User user = newUser();
        Instant expires = NOW.plus(Duration.ofDays(2));
        existing(user, 20, expires, "REVIEW_CAMPAIGN");

        service.grantReplacing(user, 10, "LOYALTY", NOW);

        AccountDiscount d = reload(user);
        assertThat(d.getPercent()).isEqualTo(20);
        assertThat(d.getSource()).isEqualTo("REVIEW_CAMPAIGN");
        assertThat(d.getExpiresAt()).isEqualTo(expires);
    }

    @Test
    @DisplayName("active row with an equal percent keeps the later expiry")
    void equalKeepsLaterExpiry() {
        User user = newUser();
        Instant soon = NOW.plus(Duration.ofDays(2));
        existing(user, 10, soon, "REVIEW_CAMPAIGN");

        service.grantReplacing(user, 10, "LOYALTY", NOW);

        assertThat(reload(user).getExpiresAt()).isEqualTo(NOW.plus(SEVEN_DAYS));
        assertThat(reload(user).getSource()).isEqualTo("LOYALTY");

        User other = newUser();
        Instant later = NOW.plus(Duration.ofDays(9));
        existing(other, 10, later, "REVIEW_CAMPAIGN");

        service.grantReplacing(other, 10, "LOYALTY", NOW);

        assertThat(reload(other).getExpiresAt()).isEqualTo(later);
        assertThat(reload(other).getSource()).isEqualTo("LOYALTY");
    }

    @Test
    @DisplayName("active row with a lower percent is raised, renewed and relabelled")
    void lowerIsRaised() {
        User user = newUser();
        existing(user, 5, NOW.plus(Duration.ofDays(1)), "REVIEW_CAMPAIGN");

        service.grantReplacing(user, 10, "LOYALTY", NOW);

        AccountDiscount d = reload(user);
        assertThat(d.getPercent()).isEqualTo(10);
        assertThat(d.getSource()).isEqualTo("LOYALTY");
        assertThat(d.getGrantedAt()).isEqualTo(NOW);
        assertThat(d.getExpiresAt()).isEqualTo(NOW.plus(SEVEN_DAYS));
    }
}
