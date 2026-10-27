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

/** Per-user account discount: granted once ever, valid 7 days, usable once. */
@SpringBootTest
@Transactional
class AccountDiscountServiceTest {

    @Autowired private AccountDiscountService service;
    @Autowired private AccountDiscountRepository repo;
    @Autowired private UserRepository userRepo;

    private User newUser() {
        return userRepo.save(User.builder()
            .email("disc-" + System.nanoTime() + "@test.arias.com")
            .passwordHash("x")
            .role(Role.EMPLOYEE)
            .active(true)
            .build());
    }

    @Test
    @DisplayName("grantIfAbsent grants 10% for 7 days the first time")
    void grantsOnce() {
        User user = newUser();
        Instant before = Instant.now();

        boolean granted = service.grantIfAbsent(user, 10, "test");

        assertThat(granted).isTrue();
        AccountDiscount d = repo.findByUserId(user.getId()).orElseThrow();
        assertThat(d.getPercent()).isEqualTo(10);
        assertThat(d.getSource()).isEqualTo("test");
        assertThat(Duration.between(d.getGrantedAt(), d.getExpiresAt())).isEqualTo(Duration.ofDays(7));
        assertThat(d.getGrantedAt()).isBetween(before.minusSeconds(5), Instant.now().plusSeconds(5));
    }

    @Test
    @DisplayName("grantIfAbsent never grants a second discount to the same user, even after use")
    void neverGrantsTwice() {
        User user = newUser();
        assertThat(service.grantIfAbsent(user, 10, "test")).isTrue();
        AccountDiscount first = repo.findByUserId(user.getId()).orElseThrow();
        first.setConsumedAt(Instant.now());
        repo.saveAndFlush(first);

        assertThat(service.grantIfAbsent(user, 20, "again")).isFalse();

        assertThat(repo.count()).isEqualTo(repo.findAll().stream().map(x -> x.getUser().getId()).distinct().count());
        assertThat(repo.findByUserId(user.getId()).orElseThrow().getPercent()).isEqualTo(10);
    }

    @Test
    @DisplayName("findActive returns the discount only while unexpired, unconsumed and unreserved")
    void findActiveHonoursLifecycle() {
        User user = newUser();
        service.grantIfAbsent(user, 10, "test");
        AccountDiscount d = repo.findByUserId(user.getId()).orElseThrow();
        Instant now = d.getGrantedAt().plus(Duration.ofDays(1));

        assertThat(service.findActive(user.getId(), now)).isPresent();
        assertThat(service.findActive(user.getId(), now).orElseThrow().percent()).isEqualTo(10);

        // expired
        assertThat(service.findActive(user.getId(), d.getExpiresAt())).isEmpty();
        assertThat(service.findActive(user.getId(), d.getExpiresAt().plusSeconds(1))).isEmpty();

        // consumed
        d.setConsumedAt(now);
        assertThat(service.findActive(user.getId(), now)).isEmpty();
    }

    @Test
    @DisplayName("findActive is empty for a user that never got a discount")
    void noDiscountNoActive() {
        assertThat(service.findActive(newUser().getId(), Instant.now())).isEmpty();
    }
}
