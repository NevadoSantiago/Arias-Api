package com.arias.credits.discount;

import com.arias.payments.CreditPurchaseRepository;
import com.arias.users.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A raised discount that a pending purchase holds keeps that reservation. */
class AccountDiscountGrantReplacingReservedTest {

    private static final Instant NOW = Instant.parse("2026-11-10T15:00:00Z");

    @Test
    @DisplayName("lower active percent that is reserved keeps reserved_purchase_id when raised")
    void raisedKeepsReservation() {
        AccountDiscountRepository repo = mock(AccountDiscountRepository.class);
        AccountDiscountService service = new AccountDiscountService(repo, mock(CreditPurchaseRepository.class),
            Clock.fixed(NOW, ZoneOffset.UTC));
        User user = User.builder().id(7L).build();
        UUID purchase = UUID.randomUUID();
        AccountDiscount row = AccountDiscount.builder().user(user).percent(5).source("REVIEW_CAMPAIGN")
            .grantedAt(NOW.minus(Duration.ofDays(1))).expiresAt(NOW.plus(Duration.ofDays(1)))
            .reservedPurchaseId(purchase).build();
        when(repo.findByUserIdForUpdate(7L)).thenReturn(Optional.of(row));

        service.grantReplacing(user, 10, "LOYALTY", NOW);

        assertThat(row.getPercent()).isEqualTo(10);
        assertThat(row.getReservedPurchaseId()).isEqualTo(purchase);
        assertThat(row.getExpiresAt()).isEqualTo(NOW.plus(AccountDiscountService.VALIDITY));
    }
}
