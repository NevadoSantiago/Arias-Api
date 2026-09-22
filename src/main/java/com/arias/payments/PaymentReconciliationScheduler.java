package com.arias.payments;

import com.arias.payments.mercadopago.MercadoPagoProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * Reconciliación horaria de compras {@code PENDING} (unidad 11, tarea 11.6,
 * diseño §Flujo de datos "Reconciliación"): cubre el webhook perdido, que es
 * el modo de falla real del flujo. Más de 30 min sin resolución → se
 * re-consulta a Mercado Pago por {@code external_reference}; más de 24 h sin
 * ningún pago reportado → {@code EXPIRED}.
 *
 * <p>Inerte si Mercado Pago no está configurado (mismo patrón que {@code
 * MercadoPagoAdapter}): no intenta ninguna llamada de red.
 *
 * <p>Delega toda mutación a {@link CreditPurchaseService} — nunca se
 * auto-invoca un método {@code @Transactional} propio, porque el proxy de
 * Spring no intercepta llamadas dentro de la misma instancia.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentReconciliationScheduler {

    private static final int PENDING_RECONCILE_MINUTES = 30;
    private static final int PENDING_EXPIRE_HOURS = 24;

    private final CreditPurchaseRepository purchaseRepo;
    private final CreditPurchaseService purchaseService;
    private final PaymentGateway paymentGateway;
    private final MercadoPagoProperties mercadoPagoProps;
    private final Clock clock;

    @Scheduled(cron = "0 0 * * * *")
    public void reconcile() {
        if (!mercadoPagoProps.isConfigured()) {
            return;
        }

        Instant now = clock.instant();
        Instant reconcileCutoff = now.minus(PENDING_RECONCILE_MINUTES, ChronoUnit.MINUTES);
        Instant expireCutoff = now.minus(PENDING_EXPIRE_HOURS, ChronoUnit.HOURS);

        List<CreditPurchase> pending = purchaseRepo.findByStatusAndCreatedAtBefore(
            CreditPurchaseStatus.PENDING, reconcileCutoff);

        int reconciled = 0;
        int expired = 0;
        for (CreditPurchase purchase : pending) {
            if (purchase.getCreatedAt().isBefore(expireCutoff)) {
                purchaseService.expirePendingPurchase(purchase.getId());
                expired++;
                continue;
            }
            if (reconcileOne(purchase.getId())) {
                reconciled++;
            }
        }

        if (reconciled > 0 || expired > 0) {
            log.info("[CRON-PAYMENT-RECONCILE] {} compra(s) reconciliada(s), {} expirada(s)",
                reconciled, expired);
        }
    }

    /**
     * Re-consulta por {@code external_reference} FUERA de cualquier
     * transacción/lock (el llamado de red va primero, igual que el webhook)
     * y delega la mutación a {@link CreditPurchaseService#applySnapshot}.
     */
    private boolean reconcileOne(java.util.UUID purchaseId) {
        Optional<PaymentSnapshot> snapshot = paymentGateway.findByExternalReference(purchaseId.toString());
        if (snapshot.isEmpty()) {
            return false; // sin pago reportado todavía — se reintenta en la próxima corrida, hasta las 24 h
        }
        purchaseService.applySnapshot(snapshot.get());
        return true;
    }
}
