package com.arias.marketing;

import com.arias.email.EmailService;
import com.arias.email.OutgoingEmail;
import com.arias.orders.notifications.NotificationRunLog;
import com.arias.orders.notifications.NotificationRunLogId;
import com.arias.orders.notifications.NotificationRunLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Every day at 12:06 (Buenos Aires): customers who ate yesterday and never had an account
 * discount get one, plus the review/discount email. Off unless
 * {@code arias.marketing.review-campaign-enabled} is true.
 *
 * <p>Idempotent per day through {@code notification_run_log} (PK {@code (tipo, fecha)}), the
 * same atomic-claim pattern as the other schedulers. Each customer also gets the discount (and
 * so the email) only once in their life, whatever the run log says.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReviewDiscountScheduler {

    private static final String TIPO = "REVIEW_DISCOUNT";
    private static final int BATCH_SIZE = 100;

    private final MarketingProperties marketing;
    private final ReviewDiscountCampaign campaign;
    private final EmailService emailService;
    private final NotificationRunLogRepository runLogRepo;
    private final Clock clock;

    @Scheduled(cron = "0 6 12 * * *", zone = "America/Argentina/Buenos_Aires")
    public void runDaily() {
        if (!marketing.reviewCampaignEnabled()) {
            log.info("[CRON-REVIEW-DISCOUNT] arias.marketing.review-campaign-enabled=false — campaña apagada");
            return;
        }

        LocalDate today = LocalDate.now(clock);
        if (!claimRunSlot(today)) {
            return;
        }

        List<OutgoingEmail> emails = campaign.grantAndPrepareEmails(today.minusDays(1));
        for (int from = 0; from < emails.size(); from += BATCH_SIZE) {
            emailService.sendBatch(emails.subList(from, Math.min(from + BATCH_SIZE, emails.size())));
        }
        updateRecipientCount(today, emails.size());
        log.info("[CRON-REVIEW-DISCOUNT] {} — {} mail(s) enviados", today, emails.size());
    }

    /** Atomic claim: the PK collision rejects a second run on the same day. */
    private boolean claimRunSlot(LocalDate fecha) {
        NotificationRunLogId id = new NotificationRunLogId(TIPO, fecha);
        if (runLogRepo.existsById(id)) return false;
        try {
            runLogRepo.save(NotificationRunLog.builder()
                .tipo(TIPO)
                .fecha(fecha)
                .sentAt(Instant.now(clock))
                .recipients(0)
                .build());
            return true;
        } catch (DataIntegrityViolationException e) {
            return false;
        }
    }

    private void updateRecipientCount(LocalDate fecha, int count) {
        runLogRepo.findById(new NotificationRunLogId(TIPO, fecha)).ifPresent(l -> {
            l.setRecipients(count);
            runLogRepo.save(l);
        });
    }
}
