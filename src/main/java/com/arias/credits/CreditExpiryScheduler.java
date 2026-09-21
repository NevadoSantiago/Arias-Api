package com.arias.credits;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Job horario de vencimiento de créditos — barre todas las billeteras con
 * {@code expires_at <= now AND available > 0} y dispara {@link
 * CreditLedgerService#expireIfDue(Long)} para cada una, fila por fila con su
 * propio bloqueo pesimista (diseño §Decisión 3).
 *
 * <p>Es el complemento del guard perezoso de {@link CreditLedgerService#apply}:
 * ese guard solo actúa cuando el usuario intenta gastar. Este job asegura que
 * un usuario inactivo también vea su saldo vencido reflejado, sin esperar a
 * que vuelva a pedir.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class CreditExpiryScheduler {

    private final CreditWalletRepository walletRepo;
    private final CreditLedgerService ledgerService;
    private final Clock clock;

    /** Corre cada hora en punto — el vencimiento no es sensible al minuto exacto. */
    @Scheduled(cron = "0 0 * * * *", zone = "America/Argentina/Buenos_Aires")
    @Transactional
    public void expireDueWallets() {
        Instant now = Instant.now(clock);
        List<Long> dueUserIds = walletRepo.findUserIdsDueForExpiration(now);

        for (Long userId : dueUserIds) {
            ledgerService.expireIfDue(userId);
        }

        if (!dueUserIds.isEmpty()) {
            log.info("[CRON] Vencimiento de créditos: {} billeteras procesadas", dueUserIds.size());
        }
    }
}
