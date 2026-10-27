package com.arias.marketing;

import com.arias.credits.discount.AccountDiscountRepository;
import com.arias.email.EmailService;
import com.arias.orders.notifications.NotificationRunLogRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Without a configured Google review link the campaign is off: nothing is granted, mailed or logged. */
@SpringBootTest(properties = "arias.marketing.google-review-url=")
@Transactional
class ReviewDiscountDisabledTest {

    @Autowired private ReviewDiscountScheduler scheduler;
    @Autowired private AccountDiscountRepository discountRepo;
    @Autowired private NotificationRunLogRepository runLogRepo;

    @MockitoBean
    private EmailService emailService;

    @Test
    @DisplayName("blank google-review-url disables the campaign")
    void disabledWithoutReviewUrl() {
        long discountsBefore = discountRepo.count();
        long runsBefore = runLogRepo.count();

        scheduler.runDaily();

        assertThat(discountRepo.count()).isEqualTo(discountsBefore);
        assertThat(runLogRepo.count()).isEqualTo(runsBefore);
        verify(emailService, never()).sendBatch(anyList());
    }
}
